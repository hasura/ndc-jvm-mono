package io.hasura.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.hasura.ndc.common.ColumnSchemaRow
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.JdbcUrlConfig
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import io.hasura.ndc.ir.ForeignKeyConstraint
import org.jooq.impl.DSL

object SnowflakeConfigGenerator : IConfigGenerator {
    private val mapper = jacksonObjectMapper()

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

        // Don't use Arrow memory format so we don't need to --add-opens=java.base/java.nio=ALL-UNNAMED to the JVM
        val modifiedJdbcUrl = jdbcUrlString.find { it == '?' }
            ?.let { "$jdbcUrlString&JDBC_QUERY_RESULT_FORMAT=JSON" }
            ?: "$jdbcUrlString?JDBC_QUERY_RESULT_FORMAT=JSON"

        val ctx = DSL.using(modifiedJdbcUrl)

        val database = ctx.fetchOne("SELECT CURRENT_DATABASE() AS DATABASE")
            ?.get("DATABASE", String::class.java)
            ?: throw Exception("Could not determine current database")

        val schemaSelection = schemas.joinToString(", ") { "'$it'" }

        ctx.fetch("SHOW PRIMARY KEYS IN DATABASE $database")
        ctx.fetch("SHOW IMPORTED KEYS IN DATABASE $database")


        //language=Snowflake
        val sql = """
        SELECT
            array_construct(tables.TABLE_DATABASE, tables.TABLE_SCHEMA, tables.TABLE_NAME) AS TABLE_NAME,
            tables.TABLE_TYPE,
            tables.COMMENT as DESCRIPTION,
            cols.COLUMNS,
            pks.PK_COLUMNS,
            fks.FOREIGN_KEYS
        FROM (
                SELECT CATALOG_NAME AS TABLE_DATABASE, db_tables.TABLE_SCHEMA, db_tables.TABLE_NAME, db_tables.COMMENT, db_tables.TABLE_TYPE
                FROM INFORMATION_SCHEMA.TABLES AS db_tables
                CROSS JOIN INFORMATION_SCHEMA.INFORMATION_SCHEMA_CATALOG_NAME
                WHERE db_tables.TABLE_TYPE IN ('BASE TABLE', 'VIEW')
                AND ${if (schemas.isEmpty()) "db_tables.TABLE_SCHEMA <> 'INFORMATION_SCHEMA'" else "db_tables.TABLE_SCHEMA IN ($schemaSelection)"}
        ) tables
        LEFT OUTER JOIN (
            SELECT
                columns.TABLE_SCHEMA,
                columns.TABLE_NAME,
                array_agg(object_construct(
                    'name', columns.column_name,
                    'description', columns.comment,
                    'type', columns.data_type,
                    'numeric_precision', columns.numeric_precision,
                    'numeric_scale', columns.numeric_scale,
                    'nullable', to_boolean(columns.is_nullable),
                    'auto_increment', to_boolean(columns.is_identity)
                )) as COLUMNS
            FROM INFORMATION_SCHEMA.COLUMNS columns
            GROUP BY columns.TABLE_SCHEMA, columns.TABLE_NAME
        ) AS cols ON cols.TABLE_SCHEMA = tables.TABLE_SCHEMA AND cols.TABLE_NAME = tables.TABLE_NAME
        LEFT OUTER JOIN (
            SELECT
                primary_keys."schema_name" AS TABLE_SCHEMA,
                primary_keys."table_name" AS TABLE_NAME,
                array_agg(primary_keys."column_name") WITHIN GROUP (ORDER BY primary_keys."key_sequence" ASC) AS PK_COLUMNS
            FROM table(RESULT_SCAN(LAST_QUERY_ID(-2))) AS primary_keys
            GROUP BY primary_keys."schema_name", primary_keys."table_name"
        ) AS pks ON pks.TABLE_SCHEMA = tables.TABLE_SCHEMA AND pks.TABLE_NAME = tables.TABLE_NAME
        LEFT OUTER JOIN (
            SELECT
                fks."fk_schema_name" AS TABLE_SCHEMA,
                fks."fk_table_name" AS TABLE_NAME,
                object_agg(
                    fks."fk_name", to_variant(fks."constraint")
                ) AS FOREIGN_KEYS
            FROM (
                SELECT
                    foreign_keys."fk_schema_name",
                    foreign_keys."fk_table_name",
                    foreign_keys."fk_name",
                    object_construct(
                        'foreign_collection', array_construct(foreign_keys."pk_database_name", foreign_keys."pk_schema_name", foreign_keys."pk_table_name"),
                        'column_mapping', object_agg(foreign_keys."fk_column_name", to_variant(foreign_keys."pk_column_name"))
                    ) AS "constraint"
                FROM table(RESULT_SCAN(LAST_QUERY_ID(-1))) AS foreign_keys
                GROUP BY foreign_keys."fk_schema_name", foreign_keys."fk_table_name", foreign_keys."fk_name", foreign_keys."pk_database_name", foreign_keys."pk_schema_name", foreign_keys."pk_table_name"
            ) AS fks
            GROUP BY fks."fk_schema_name", fks."fk_table_name"
        ) AS fks ON fks.TABLE_SCHEMA = tables.TABLE_SCHEMA AND fks.TABLE_NAME = tables.TABLE_NAME
        """.trimIndent()

        val tables = ctx.fetch(sql).map { row ->
            val columns = row
                .get("COLUMNS", String::class.java)
                .let { mapper.readValue<List<ColumnSchemaRow>>(it) }

            val pks = columns.filter { it.is_primarykey ?: false }

            TableSchemaRow(
                // Generate full table name, e.g. "DATABASE.SCHEMA.TABLE"
                tableName = row.get("TABLE_NAME", String::class.java)
                    .let { mapper.readValue<List<String>>(it) }
                    .joinToString("."),
                tableType = when (val tableType = row.get("TABLE_TYPE", String::class.java)) {
                    "BASE TABLE" -> TableType.TABLE
                    "VIEW" -> TableType.VIEW
                    else -> throw Exception("Unknown table type: $tableType")
                },
                description = row.get("DESCRIPTION", String::class.java),
                columns = columns,
                pks = pks.map { it.name },
                // Read the JSON value, convert "foreign_collection" from ["DATABASE", "SCHEMA", "TABLE"] to "DATABASE.SCHEMA.TABLE"
                fks = row.get("FOREIGN_KEYS", String::class.java)
                    ?.let {
                        mapper.readValue<Map<String, Map<String, Any>>>(it)
                            .mapValues { (_, value) ->
                                ForeignKeyConstraint(
                                    column_mapping = (value["column_mapping"] as Map<String, String>),
                                    foreign_collection = (value["foreign_collection"] as List<String>).joinToString(".")
                                )
                            }
                    }

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