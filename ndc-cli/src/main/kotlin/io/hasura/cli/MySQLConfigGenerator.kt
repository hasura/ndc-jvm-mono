package io.hasura.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.hasura.ndc.common.ColumnSchemaRow
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.JdbcUrlConfig
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import org.jooq.impl.DSL

object MySQLConfigGenerator : IConfigGenerator {
    private val mapper = jacksonObjectMapper()

    override fun generateConfig(
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

        //language=MySQL
        val sql = """
            SELECT
                ${if (fullyQualifyNames) {
                    "concat(tables.TABLE_SCHEMA, '.', tables.TABLE_NAME) AS TABLE_NAME,"
                } else {
                    "tables.TABLE_NAME AS TABLE_NAME,"
                }}
                tables.TABLE_TYPE,
                tables.table_COMMENT as DESCRIPTION,
                cols.COLUMNS,
                fks.FOREIGN_KEYS
            FROM (
                SELECT *
                FROM INFORMATION_SCHEMA.TABLES tables
                WHERE TABLE_TYPE IN ('BASE TABLE', 'VIEW')
                AND tables.TABLE_SCHEMA = DATABASE()
            ) tables
            LEFT OUTER JOIN (
                SELECT
                    columns.TABLE_SCHEMA,
                    columns.TABLE_NAME,
                    json_arrayagg(json_object(
                        'name', columns.column_name,
                        'description', columns.column_comment,
                        'type', columns.data_type,
                        'numeric_precision', columns.numeric_precision,
                        'numeric_scale', columns.numeric_scale,
                        'nullable', if (columns.is_nullable = 'yes', true, false),
                        'auto_increment', if(columns.extra = 'auto_increment',true,false),
                        'is_primarykey', if(columns.COLUMN_KEY = 'PRI', true, false)
                    )) as COLUMNS
                FROM INFORMATION_SCHEMA.COLUMNS columns
                GROUP BY columns.TABLE_SCHEMA, columns.TABLE_NAME
            ) AS cols ON cols.TABLE_SCHEMA = tables.TABLE_SCHEMA AND cols.TABLE_NAME = tables.TABLE_NAME
            LEFT OUTER JOIN (
                SELECT
                    keys.TABLE_SCHEMA,
                    keys.TABLE_NAME,
                    json_objectagg(keys.constraint_name,json_object(
                        'foreign_collection', concat(keys.TABLE_SCHEMA, '.', keys.REFERENCED_TABLE_NAME),
                        'column_mapping', json_object(keys.COLUMN_NAME,keys.REFERENCED_COLUMN_NAME)
                    )) AS "FOREIGN_KEYS"
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE as `keys` where REFERENCED_TABLE_NAME is not null
                GROUP BY keys.TABLE_SCHEMA, keys.TABLE_NAME
            ) AS fks ON fks.TABLE_SCHEMA = tables.TABLE_SCHEMA AND fks.TABLE_NAME = tables.TABLE_NAME
        """.trimIndent()

        val tables = ctx.fetch(sql).map { row ->
            val columns = row
                .get("COLUMNS", String::class.java)
                .let { mapper.readValue<List<ColumnSchemaRow>>(it) }

            val pks = columns.filter { it.is_primarykey ?: false }

            TableSchemaRow(
                tableName = row.get("TABLE_NAME", String::class.java),
                tableType = when (val tableType = row.get("TABLE_TYPE", String::class.java)) {
                    "BASE TABLE" -> TableType.TABLE
                    "VIEW" -> TableType.VIEW
                    else -> throw Exception("Unknown table type: $tableType")
                },
                description = row.get("DESCRIPTION", String::class.java),
                columns = columns,
                pks = pks.map { it.name },
                fks = row.get("FOREIGN_KEYS", String::class.java)?.let(mapper::readValue)
            )
        }

        return ConnectorConfiguration(
            jdbcUrl = jdbcUrl,
            jdbcProperties = mapOf("allowMultiQueries" to "true"),
            tables = tables,
            functions = emptyList()
        )
    }
}
