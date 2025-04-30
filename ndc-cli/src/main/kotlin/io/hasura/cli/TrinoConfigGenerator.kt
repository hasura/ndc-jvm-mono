package io.hasura.cli

import io.hasura.ndc.common.ColumnSchemaRow
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.JdbcUrlConfig
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import org.jooq.impl.DSL

object TrinoConfigGenerator : IConfigGenerator {

    override fun getConfig(
        jdbcUrl: JdbcUrlConfig,
        schemas: List<String>,
        fullyQualifyNames: Boolean,
    ): ConnectorConfiguration {
        val jdbcUrlString = when (jdbcUrl) {
            is JdbcUrlConfig.Literal -> jdbcUrl.value
            is JdbcUrlConfig.EnvVar -> System.getenv(jdbcUrl.variable)
                ?: throw IllegalArgumentException("Environment variable ${jdbcUrl.variable} not found")
        }

        val ctx = DSL.using(jdbcUrlString)

        // "current_schema" is a special Trino value that resolves to the current schema
        // See: https://trino.io/docs/current/functions/session.html#current_schema
        val sql = """
           SELECT table_name, column_name, data_type, is_nullable
           FROM information_schema.columns
           WHERE table_schema = current_schema
        """.trimIndent()


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

        // fetch every column, use jOOQ's fetchGroups to group them by table_name
        val tables = ctx.resultQuery(sql).fetchGroups("table_name").map { (tableName, rows) ->
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
            jdbcUrl = jdbcUrl,
            jdbcProperties = emptyMap(),
            tables = tables,
            functions = emptyList()
        )
    }
}