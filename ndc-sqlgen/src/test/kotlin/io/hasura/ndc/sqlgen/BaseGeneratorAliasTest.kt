package io.hasura.ndc.sqlgen

import io.hasura.ndc.ir.*
import org.jooq.Select
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that `expressionToConditionWithSqlAlias` decouples SQL table aliasing from
 * connector-config collection lookup.
 *
 * Regression coverage for the v1.0.19 MySQL bug where a self-referential subquery's WHERE
 * predicate was looking up `<alias>_<collection>` in connector configuration and erroring with
 * "Collection products_products not found in connector configuration".
 *
 * Test connector configuration is loaded from src/test/resources/test-config/configuration.json
 * via the HASURA_CONFIGURATION_DIRECTORY env var set by the Gradle `test` task.
 */
class BaseGeneratorAliasTest {

    private val gen: BaseQueryGenerator = object : BaseQueryGenerator() {
        override fun queryRequestToSQL(request: QueryRequest): Select<*> =
            throw NotImplementedError("not used in this test")

        override fun forEachQueryRequestToSQL(request: QueryRequest): Select<*> =
            throw NotImplementedError("not used in this test")
    }

    private fun renderInline(condition: org.jooq.Condition): String =
        DSL.using(SQLDialect.MYSQL).renderInlined(condition)

    /**
     * Empty-path predicate inside an aliased subquery: the SQL must qualify columns with the
     * alias (the FROM-clause name), and config lookup must succeed for the real collection.
     */
    @Test
    fun `generated SQL example for PR description`() {
        val req = QueryRequest(
            collection = "regions",
            collection_relationships = emptyMap(),
            query = Query()
        )
        val predicate = Expression.ApplyBinaryComparison(
            operator = ApplyBinaryComparisonOperator.EQ,
            column = ComparisonTarget.Column(name = "name", path = emptyList()),
            value = ComparisonValue.ScalarComp("USA")
        )
        val sql = renderInline(
            gen.expressionToConditionWithSqlAlias(predicate, req, "parentRegion_regions")
        )
        println("[SQL EXAMPLE] $sql")
    }

    @Test
    fun `empty-path predicate qualifies SQL with alias but config-lookup uses real collection`() {
        val req = QueryRequest(
            collection = "products",
            collection_relationships = emptyMap(),
            query = Query()
        )
        val predicate = Expression.ApplyBinaryComparison(
            operator = ApplyBinaryComparisonOperator.EQ,
            column = ComparisonTarget.Column(name = "isMainProduct", path = emptyList()),
            value = ComparisonValue.ScalarComp("1")
        )

        val condition = gen.expressionToConditionWithSqlAlias(predicate, req, "products_products")
        val sql = renderInline(condition)

        assertTrue(sql.contains("products_products"), "SQL should qualify with alias, got: $sql")
        assertTrue(sql.contains("isMainProduct"), "SQL should reference column, got: $sql")
        assertFalse(
            sql.contains("`products`.`isMainProduct`") || sql.contains("\"products\".\"isMainProduct\""),
            "SQL should NOT use real collection name as qualifier when alias is set, got: $sql"
        )
    }

    /**
     * The 2-arg path (no alias) is unchanged and uses request.collection for both concerns.
     */
    @Test
    fun `default 2-arg path qualifies with request collection`() {
        val req = QueryRequest(
            collection = "products",
            collection_relationships = emptyMap(),
            query = Query()
        )
        val predicate = Expression.ApplyBinaryComparison(
            operator = ApplyBinaryComparisonOperator.EQ,
            column = ComparisonTarget.Column(name = "active", path = emptyList()),
            value = ComparisonValue.ScalarComp(true)
        )

        val condition = gen.expressionToCondition(predicate, req)
        val sql = renderInline(condition)

        assertTrue(sql.contains("products"), "default path qualifies with request.collection: $sql")
    }

    /**
     * Cross-table self-join scenario: outer collection products, alias products_products. With
     * the buggy v1.0.19 code, this would throw "Collection products_products not found ...".
     */
    @Test
    fun `aliased self-join predicate does not throw on config lookup`() {
        val req = QueryRequest(
            collection = "products",
            collection_relationships = emptyMap(),
            query = Query()
        )
        val predicate = Expression.ApplyBinaryComparison(
            operator = ApplyBinaryComparisonOperator.EQ,
            column = ComparisonTarget.Column(name = "isMainProduct", path = emptyList()),
            value = ComparisonValue.ScalarComp("1")
        )

        // Should not throw IllegalStateException("Collection products_products not found ...")
        gen.expressionToConditionWithSqlAlias(predicate, req, "products_products")
    }

    /**
     * Nested AND/OR/NOT propagate the SQL alias correctly without losing it on recursion.
     */
    @Test
    fun `compound predicates propagate sql alias through recursion`() {
        val req = QueryRequest(
            collection = "regions",
            collection_relationships = emptyMap(),
            query = Query()
        )
        val predicate = Expression.And(
            listOf(
                Expression.ApplyBinaryComparison(
                    operator = ApplyBinaryComparisonOperator.EQ,
                    column = ComparisonTarget.Column(name = "name", path = emptyList()),
                    value = ComparisonValue.ScalarComp("USA")
                ),
                Expression.Not(
                    Expression.ApplyBinaryComparison(
                        operator = ApplyBinaryComparisonOperator.EQ,
                        column = ComparisonTarget.Column(name = "id", path = emptyList()),
                        value = ComparisonValue.ScalarComp(0)
                    )
                )
            )
        )

        val sql = renderInline(
            gen.expressionToConditionWithSqlAlias(predicate, req, "parentRegion_regions")
        )

        assertTrue(
            sql.contains("parentRegion_regions"),
            "alias must propagate into nested expressions, got: $sql"
        )
        // count occurrences: at least 2 (one for each ApplyBinaryComparison)
        val occurrences = sql.windowed("parentRegion_regions".length).count { it == "parentRegion_regions" }
        assertEquals(2, occurrences, "alias should appear once per leaf comparison, got: $sql")
    }
}
