package io.hasura.ndc.sqlgen

import io.hasura.ndc.ir.*
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.jooq.Select

// Direct unit tests for the SQL-alias / config-collection decoupling in [BaseGenerator].
// These pin the regression introduced in v1.0.19 by PR #97: passing a SQL table alias as the
// `overrideCollection` to `expressionToCondition` caused `getColumnType` to look up the alias as
// a real collection and throw `Collection X not found in connector configuration`. The fix
// adds [expressionToConditionWithSqlAlias], which qualifies SQL fields with the alias while
// keeping config lookups against the real collection.
class BaseGeneratorAliasTest {

    // BaseGenerator is sealed within ndc-sqlgen's main source set; reach the API via
    // BaseQueryGenerator, which is an abstract class and freely subclassable from tests.
    private val generator: BaseQueryGenerator = object : BaseQueryGenerator() {
        override fun queryRequestToSQL(request: QueryRequest): Select<*> = error("not used")
        override fun forEachQueryRequestToSQL(request: QueryRequest): Select<*> = error("not used")
    }
    private val ctx = DSL.using(SQLDialect.MYSQL)

    private fun render(condition: org.jooq.Condition): String =
        ctx.renderInlined(condition)

    private fun eqString(name: String, value: String) =
        Expression.ApplyBinaryComparison(
            operator = ApplyBinaryComparisonOperator.EQ,
            column = ComparisonTarget.Column(name = name, path = emptyList()),
            value = ComparisonValue.ScalarComp(value)
        )

    private fun selfReferentialRequest(predicate: Expression): QueryRequest = QueryRequest(
        collection = "regions",
        query = Query(predicate = predicate),
        collection_relationships = mapOf(
            "regions_self" to Relationship(
                column_mapping = mapOf("parentRegionId" to "id"),
                relationship_type = RelationshipType.Object,
                target_collection = "regions",
                arguments = emptyMap()
            )
        )
    )

    @Test
    @DisplayName("self-join with WHERE: empty-path column qualifies with SQL alias, config lookup uses real collection")
    fun selfJoinWithWhere_qualifiesWithAlias_andResolvesConfig() {
        val predicate = eqString("name", "USA")
        val request = selfReferentialRequest(predicate)

        // Pre-fix: this threw "Collection parentRegion_regions not found in connector configuration".
        val condition = generator.expressionToConditionWithSqlAlias(
            predicate,
            request,
            sqlTableAlias = "parentRegion_regions"
        )
        val sql = render(condition)

        assertTrue(
            sql.contains("parentRegion_regions") && sql.contains("name"),
            "SQL should qualify column with the alias: $sql"
        )
        assertFalse(
            sql.contains("regions.`name`") || sql.contains("`regions`.`name`"),
            "SQL should NOT qualify column with the real collection name when an alias is supplied: $sql"
        )
    }

    @Test
    @DisplayName("no alias: empty-path column qualifies with the real collection name")
    fun noAlias_qualifiesWithCollection() {
        val predicate = eqString("name", "USA")
        val request = selfReferentialRequest(predicate)

        val condition = generator.expressionToCondition(predicate, request)
        val sql = render(condition)

        assertTrue(sql.contains("regions") && sql.contains("name"), "SQL should reference regions.name: $sql")
    }

    @Test
    @DisplayName("legacy 3-arg overrideCollection still swaps both SQL and config (used by Unrelated Exists)")
    fun legacyOverrideCollection_stillSwaps() {
        // When overrideCollection is a real collection name, both SQL and config should target it.
        val predicate = eqString("active", "true")
        val request = QueryRequest(
            collection = "regions",
            query = Query(predicate = predicate),
            collection_relationships = emptyMap()
        )
        val condition = generator.expressionToCondition(predicate, request, overrideCollection = "products")
        val sql = render(condition)
        assertTrue(sql.contains("products") && sql.contains("active"), "SQL should reference products.active: $sql")
    }

    @Test
    @DisplayName("non-empty path: column qualifies with the path-traversed target collection regardless of SQL alias")
    fun pathTraversal_unaffectedByAlias() {
        // WHERE products(via relationship).active = true — empty path on the parent.
        // We exercise non-empty path by referencing a column through a relationship.
        val predicate = Expression.ApplyBinaryComparison(
            operator = ApplyBinaryComparisonOperator.EQ,
            column = ComparisonTarget.Column(
                name = "id",
                path = listOf(
                    PathElement(
                        relationship = "regions_self",
                        arguments = emptyMap(),
                        predicate = Expression.And(emptyList())
                    )
                )
            ),
            value = ComparisonValue.ScalarComp(1)
        )
        val request = selfReferentialRequest(predicate)

        // sqlTableAlias should NOT affect path-traversed columns — they qualify with target_collection.
        val aliased = render(
            generator.expressionToConditionWithSqlAlias(predicate, request, sqlTableAlias = "parentRegion_regions")
        )
        val plain = render(generator.expressionToCondition(predicate, request))

        assertTrue(aliased.contains("regions") && aliased.contains("id"), "Path-traversed column should reference target collection: $aliased")
        // The two should produce identical SQL for path-traversed columns.
        assert(aliased == plain) { "Path-traversed column qualification should be alias-agnostic. aliased=$aliased plain=$plain" }
    }
}
