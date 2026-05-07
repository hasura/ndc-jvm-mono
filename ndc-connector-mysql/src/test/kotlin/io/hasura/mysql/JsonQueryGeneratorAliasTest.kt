package io.hasura.mysql

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.hasura.ndc.ir.QueryRequest
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// End-to-end SQL-rendering tests for JsonQueryGenerator covering the v1.0.19 nested-WHERE
// regression and confirming PR #97's self-join fix is preserved. We render generated jOOQ
// queries with a MYSQL dialect DSLContext (no DB required) and assert on the SQL string.
//
// Fixtures live under ndc-ir/src/test/resources/queryRequests/ and are intentionally shared
// with other connectors so format drift surfaces quickly. Test execution requires
// HASURA_CONFIGURATION_DIRECTORY to point at a configuration.json describing the tables —
// configured by ndc-connector-mysql/build.gradle.kts.
class JsonQueryGeneratorAliasTest {

    private val mapper = jacksonObjectMapper()
    private val ctx = DSL.using(SQLDialect.MYSQL_8_0_31)

    private fun loadRequest(name: String): QueryRequest {
        val resource = this::class.java.getResource("/queryRequests/$name")
            ?: error("missing fixture queryRequests/$name")
        return mapper.readValue(resource.readText(Charsets.UTF_8))
    }

    private fun renderSQL(name: String): String {
        val request = loadRequest(name)
        val query = JsonQueryGenerator.queryRequestToSQL(request)
        return ctx.renderInlined(query)
    }

    @Test
    @DisplayName("Case 1 — Self-join WITHOUT WHERE (PR #97): inner subquery is uniquely aliased")
    fun selfJoinWithoutWhere_uniqueAlias() {
        val sql = renderSQL("self_referential_object_relationship.json")
        assertTrue(
            sql.contains("parentRegion_regions"),
            "Self-join subquery should be aliased as parentRegion_regions: $sql"
        )
    }

    @Test
    @DisplayName("Case 2 — Self-join WITH WHERE: alias used in JOIN + WHERE; no \"Collection X not found\" error")
    fun selfJoinWithWhere_usesAliasInWhere() {
        // Pre-fix this threw: "Collection parentRegion_regions not found in connector configuration".
        val sql = renderSQL("self_referential_object_relationship_with_where.json")
        assertTrue(sql.contains("parentRegion_regions"), "Should reference SQL alias: $sql")
        assertTrue(sql.contains("USA"), "Should inline the predicate value: $sql")
    }

    @Test
    @DisplayName("Case 3 — Nested cross-table with WHERE (the regression): generates without throwing")
    fun nestedCrossTableWithWhere_regression() {
        // The regression: nested relationships pass `${alias}_${target_collection}` into the
        // recursive call, then expressionToCondition tried to look up that alias as a real
        // collection. With the fix the alias is used only for SQL qualification.
        val sql = renderSQL("nested_cross_table_with_where.json")
        assertTrue(sql.contains("products"), "Should reference products table: $sql")
        // The innermost subquery is aliased "products_products" (alias_target).
        assertTrue(
            sql.contains("products_products"),
            "Innermost subquery should carry the products_products alias: $sql"
        )
        assertFalse(
            sql.contains("Collection products_products not found"),
            "Pre-fix error should not appear: $sql"
        )
    }

    @Test
    @DisplayName("Case 4 — Single-level nested WHERE: filter uses alias-qualified column")
    fun singleLevelNestedWithWhere() {
        val sql = renderSQL("single_level_cross_table_with_where.json")
        assertTrue(sql.contains("products_products"), "Should alias the products subquery: $sql")
        assertTrue(sql.contains("active"), "Should reference the predicate column: $sql")
    }

    @Test
    @DisplayName("Case 5 — Deep nesting with mixed self-join + cross-table: all WHERE filters render")
    fun deepNestingMixed() {
        val sql = renderSQL("deep_nested_mixed_self_join_and_cross_table.json")
        assertTrue(sql.contains("parentRegion_regions"), "Self-join alias present: $sql")
        assertTrue(sql.contains("products_products"), "Cross-table alias present: $sql")
        assertTrue(sql.contains("USA") && sql.contains("Antarctica"),
            "Both nested predicate values should appear: $sql")
    }
}
