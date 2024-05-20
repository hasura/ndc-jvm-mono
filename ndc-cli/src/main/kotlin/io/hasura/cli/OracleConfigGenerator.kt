package io.hasura.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import org.jooq.impl.DSL

object OracleConfigGenerator {
    private val mapper = jacksonObjectMapper()

    fun getConfig(
        jdbcUrl: String,
        schemas: List<String> = emptyList()
    ): ConnectorConfiguration {
        val ctx = DSL.using(jdbcUrl)

        //language=Oracle
        val baseTableSql = """
            SELECT *
            FROM (
                SELECT tables.OWNER, tables.TABLE_NAME, 'TABLE' AS TABLE_TYPE
                FROM ALL_TABLES tables
                WHERE tables.TEMPORARY = 'N'
                UNION ALL
                SELECT views.OWNER, views.VIEW_NAME, 'VIEW' AS TABLE_TYPE
                FROM ALL_VIEWS views
            ) tables
        """ + when {
            schemas.isEmpty() -> ""
            else -> "WHERE tables.OWNER IN (${schemas.joinToString(",") { "'$it'" }})"
        }

        val sql =
            //language=Oracle
            """
            SELECT
                tables.OWNER as Owner,
                tables.TABLE_NAME AS TableName,
                tables.TABLE_TYPE AS TableType,
                table_comments.COMMENTS AS Description,
                columns.Columns,
                pks.PrimaryKeys,
                fks.ForeignKeys
            FROM (
                $baseTableSql
            ) tables
            INNER JOIN (
                    SELECT OWNER, TABLE_NAME, COMMENTS FROM ALL_TAB_COMMENTS
                    UNION ALL
                    SELECT OWNER, MVIEW_NAME AS TABLE_NAME, COMMENTS FROM ALL_MVIEW_COMMENTS
                ) table_comments
                ON tables.OWNER = table_comments.OWNER AND tables.TABLE_NAME = table_comments.TABLE_NAME
            INNER JOIN ALL_USERS users
                ON users.USERNAME = tables.OWNER
            LEFT JOIN ( -- Must be a LEFT JOIN because INNER performs very poorly for an unknown reason
                    SELECT
                        columns.OWNER,
                        columns.TABLE_NAME,
                        (
                            json_arrayagg(
                                json_object(
                                    'name' VALUE columns.COLUMN_NAME,
                                    'description' VALUE column_comments.COMMENTS,
                                    'type' VALUE columns.DATA_TYPE,
                                    'numeric_scale' VALUE columns.DATA_SCALE,
                                    'nullable' VALUE case when columns.NULLABLE = 'Y' then 'true' else 'false' end,
                                    'auto_increment' VALUE case when columns.IDENTITY_COLUMN = 'YES' then 'true' else 'false' end
                                )
                                ORDER BY columns.COLUMN_ID
                                RETURNING CLOB
                            )
                        ) AS Columns
                    FROM ALL_TAB_COLUMNS columns
                    LEFT OUTER JOIN ALL_COL_COMMENTS column_comments
                        ON columns.OWNER = column_comments.OWNER
                        AND columns.TABLE_NAME = column_comments.TABLE_NAME
                        AND columns.COLUMN_NAME = column_comments.COLUMN_NAME
                    GROUP BY columns.OWNER, columns.TABLE_NAME
                )
                columns
                ON columns.OWNER = tables.OWNER
                AND columns.TABLE_NAME = tables.TABLE_NAME
            LEFT OUTER JOIN (
                    SELECT
                        pk_constraints.OWNER,
                        pk_constraints.TABLE_NAME,
                        (
                            json_arrayagg(
                                pk_columns.COLUMN_NAME
                                ORDER BY pk_columns.POSITION
                                RETURNING CLOB
                            )
                        ) AS PrimaryKeys
                    FROM ALL_CONSTRAINTS pk_constraints
                    LEFT OUTER JOIN ALL_CONS_COLUMNS pk_columns
                        ON pk_constraints.CONSTRAINT_NAME = pk_columns.CONSTRAINT_NAME
                        AND pk_constraints.OWNER = pk_columns.OWNER
                        AND pk_constraints.TABLE_NAME = pk_columns.TABLE_NAME
                    WHERE pk_constraints.CONSTRAINT_TYPE = 'P'
                    GROUP BY pk_constraints.OWNER, pk_constraints.TABLE_NAME
                )
                pks
                ON pks.OWNER = tables.OWNER
                AND pks.TABLE_NAME = tables.TABLE_NAME
            LEFT OUTER JOIN LATERAL (
                    SELECT
                        fks.OWNER,
                        fks.TABLE_NAME,
                        (
                            json_objectagg (
                                fks.FK_CONSTRAINT_NAME VALUE fks.Constraint
                                RETURNING CLOB
                            )
                        ) AS ForeignKeys
                    FROM (
                        SELECT
                            fk_constraints.OWNER,
                            fk_constraints.TABLE_NAME,
                            fk_constraints.CONSTRAINT_NAME AS FK_CONSTRAINT_NAME,
                            json_object(
                                'foreign_collection' VALUE fk_pk_constraints.OWNER || '.' || fk_pk_constraints.TABLE_NAME,
                                'column_mapping' VALUE (
                                    json_objectagg (
                                        fk_columns.COLUMN_NAME VALUE fk_pk_columns.COLUMN_NAME
                                    )
                                )
                            ) AS Constraint
                        FROM ALL_CONSTRAINTS fk_constraints
                        INNER JOIN ALL_CONSTRAINTS fk_pk_constraints
                            ON fk_pk_constraints.OWNER = fk_constraints.R_OWNER
                            AND fk_pk_constraints.CONSTRAINT_NAME = fk_constraints.R_CONSTRAINT_NAME
                        INNER JOIN ALL_CONS_COLUMNS fk_columns
                            ON fk_columns.OWNER = fk_constraints.OWNER
                            AND fk_columns.TABLE_NAME = fk_constraints.TABLE_NAME
                            AND fk_columns.CONSTRAINT_NAME = fk_constraints.CONSTRAINT_NAME
                        INNER JOIN ALL_CONS_COLUMNS fk_pk_columns
                            ON fk_pk_columns.OWNER = fk_pk_constraints.OWNER
                            AND fk_pk_columns.TABLE_NAME = fk_pk_constraints.TABLE_NAME
                            AND fk_pk_columns.CONSTRAINT_NAME = fk_pk_constraints.CONSTRAINT_NAME
                            AND fk_pk_columns.POSITION = fk_columns.POSITION
                            AND fk_constraints.CONSTRAINT_TYPE = 'R'
                        WHERE fk_constraints.OWNER = tables.OWNER AND fk_constraints.TABLE_NAME = tables.TABLE_NAME
                        GROUP BY fk_constraints.OWNER, fk_constraints.TABLE_NAME, fk_constraints.CONSTRAINT_NAME, fk_pk_constraints.OWNER, fk_pk_constraints.TABLE_NAME
                    ) fks
                    GROUP BY fks.OWNER, fks.TABLE_NAME
                )
                fks
                ON fks.OWNER = tables.OWNER
                AND fks.TABLE_NAME = tables.TABLE_NAME
            WHERE users.ORACLE_MAINTAINED = 'N'
            """.trimIndent()

        val tables = ctx.fetch(sql).map { row ->
            TableSchemaRow(
                tableName = "${row.get("OWNER", String::class.java)}.${row.get("TABLENAME", String::class.java)}",
                tableType = when (val tableType = row.get("TABLETYPE", String::class.java)) {
                    "TABLE" -> TableType.TABLE
                    "VIEW" -> TableType.VIEW
                    else -> throw Exception("Unknown table type: $tableType")
                },
                description = row.get("DESCRIPTION", String::class.java),
                columns = row.get("COLUMNS", String::class.java).let(mapper::readValue),
                pks = row.get("PRIMARYKEYS", String::class.java)?.let(mapper::readValue),
                fks = row.get("FOREIGNKEYS", String::class.java)?.let(mapper::readValue)
            )
        }

        return ConnectorConfiguration(
            jdbcUrl = jdbcUrl,
            tables = tables,
            functions = emptyList()
        )
    }
}
