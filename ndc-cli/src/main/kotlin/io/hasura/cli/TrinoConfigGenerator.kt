package io.hasura.cli

import io.hasura.ndc.common.ColumnSchemaRow
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.JdbcUrlConfig
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import org.jooq.impl.DSL

fun debug(message: String) {
    if (System.getenv("PROMPTQL_DEBUG") == "true") {
        println(message)
    }
}


object TrinoConfigGenerator : IConfigGenerator {

    override fun generateConfig(
        jdbcUrlConfig: JdbcUrlConfig,
        schemas: List<String>,
        fullyQualifyNames: Boolean,
    ): ConnectorConfiguration {
        val jdbcUrlString = when (jdbcUrlConfig) {
            is JdbcUrlConfig.Literal -> jdbcUrlConfig.value
            is JdbcUrlConfig.EnvVar -> System.getenv(jdbcUrlConfig.variable)
                ?: throw IllegalArgumentException("Environment variable ${jdbcUrlConfig.variable} not found")
        }

        // Parse a Trino JDBC URL to extract the catalog and schema (if present)
        fun getCatalogAndSchemaFromJdbcUrl(jdbcUrl: String): Pair<String?, String?> {
            val contents = jdbcUrl.substringAfter("jdbc:trino://")
            val parts = contents.split("/", limit = 3)
            // Theoretical parts: ["localhost:8080", "catalog", "schema"]
            return when (parts.size) {
                1 -> null to null // No catalog or schema
                2 -> parts[1] to null // Only catalog, no schema
                3 -> parts[1] to parts[2] // Both catalog and schema
                else -> error("Unexpected JDBC URL format: $jdbcUrl. Expected format is 'jdbc:trino://host:port?user=name&password=pwd'")
            }
        }

        val (parsedCatalog, parsedSchema) = getCatalogAndSchemaFromJdbcUrl(jdbcUrlString)
        if (parsedCatalog != null || parsedSchema != null) {
            error(
                "Trino JDBC URLs should not contain catalog or schema information. " +
                        "Please provide an URL in the format 'jdbc:trino://host:port?user=name&password=pwd'"
            )
        }

        // Parse the list of schemas to group the values by catalog
        // Results in: { catalogName: [schema1, schema2, ...] }
        val catalogToSchemas = schemas
            .groupBy { it.split(".").firstOrNull() }
            .mapValues { (catalog, schemaEntries) ->
                // If there are entries that are just the catalog name with no schema part, we should include all schemas for that catalog
                val hasEmptyCatalogEntry = schemaEntries.any { it == catalog }
                if (hasEmptyCatalogEntry) {
                    // Return empty list to signal we want all schemas
                    emptyList()
                } else {
                    // Extract schema parts
                    schemaEntries
                        .map { it.split(".").drop(1).joinToString("") }
                        .filter { it.isNotEmpty() } // Filter out any empty schema names
                }
            }

        // For each catalog given, we need to execute a SQL query to fetch the table and column information.
        val query = catalogToSchemas.entries.joinToString("UNION ALL\n") { (catalog, catalogSchemas) ->
            """
            SELECT table_catalog, table_schema, table_name, column_name, data_type, is_nullable
            FROM $catalog.information_schema.columns
            ${
                if (catalogSchemas.isNotEmpty()) {
                    debug("Filtering schemas for catalog $catalog: $catalogSchemas")
                    "WHERE table_schema IN (${catalogSchemas.joinToString(",") { "'$it'" }})"
                } else {
                    debug("No specific schemas for catalog $catalog, including all schemas")
                    ""
                }
            }
            """.trimIndent()
        }

        // Take a string of the format "decimal(20)" or "decimal(20,2)" and extract the numeric precision and scale
        fun extractNumericPrecisionAndScale(
            decimalString: String
        ): Pair<Int, Int> {
            val (precision, scale) = decimalString
                .substringAfter("(")
                .substringBefore(")")
                .let {
                    when {
                        it.contains(",") -> it.split(",")
                        else -> listOf(it, "0")
                    }
                }
                .map { it.toInt() }
            return precision to scale
        }

        // fetch every column, use jOOQ's fetchGroups to group them by (catalog, schema, table)
        debug("Executing query to fetch table and column information...")
        query.lines().forEach { line ->
            debug("    $line")
        }

        val tables = DSL.using(jdbcUrlString)
            .resultQuery(query)
            .fetchGroups { record ->
                val catalog = record.get("table_catalog", String::class.java)
                val schema = record.get("table_schema", String::class.java)
                val tableName = record.get("table_name", String::class.java)
                "${catalog}.${schema}.${tableName}"
            }
            .map { (tableName, rows) ->
                TableSchemaRow(
                    tableName = tableName as String,
                    tableType = TableType.TABLE,
                    description = null,
                    pks = emptyList(),
                    fks = emptyMap(),
                    columns = rows.map { row ->
                        val dataType = row.get("data_type", String::class.java)
                        val (numericPrecision, numericScale) = when {
                            dataType.startsWith("decimal") -> extractNumericPrecisionAndScale(dataType)
                            else -> null to null
                        }
                        ColumnSchemaRow(
                            name = row.get("column_name", String::class.java),
                            type = row.get("data_type", String::class.java),
                            nullable = row.get("is_nullable", String::class.java) == "YES",
                            auto_increment = false,
                            is_primarykey = false,
                            description = null,
                            numeric_precision = numericPrecision,
                            numeric_scale = numericScale
                        )
                    },
                )
            }


        return ConnectorConfiguration(
            jdbcUrl = jdbcUrlConfig,
            jdbcProperties = emptyMap(),
            tables = tables,
            functions = emptyList()
        )
    }
}
