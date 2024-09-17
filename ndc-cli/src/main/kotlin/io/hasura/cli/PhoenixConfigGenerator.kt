package io.hasura.cli

import io.hasura.ndc.common.ColumnSchemaRow
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import org.jooq.impl.DSL
import java.sql.JDBCType

object PhoenixConfigGenerator : IConfigGenerator {
    override fun getConfig(
        jdbcUrl: String,
        schemas: List<String>
    ): ConnectorConfiguration {
        val ctx = DSL.using(jdbcUrl)

        val result = ctx.fetch("""
            SELECT * FROM SYSTEM.CATALOG
            WHERE TABLE_SCHEM != 'SYSTEM' OR TABLE_SCHEM IS NULL
        """)

        val groupedBySchema = result.groupBy { it["TABLE_SCHEM"] as String? }

        val tables = groupedBySchema.map { (schema, records) ->
            val tablesMap = records.groupBy { it["TABLE_NAME"] as String }

            tablesMap.map { (tableName, records) ->
                val columns = records.filter { it["COLUMN_NAME"] != null }.map {
                    val columnFamily = it["COLUMN_FAMILY"] as String?
                    val columnName = it["COLUMN_NAME"] as String
                    ColumnSchemaRow(
                        name = if (columnFamily != null && columnFamily != "0") "$columnFamily.$columnName" else columnName,
                        description = null,
                        type = JDBCType.valueOf(it["DATA_TYPE"] as Int).name,
                        numeric_scale = null,
                        nullable = it["NULLABLE"] == 1,
                        auto_increment = it["IS_AUTOINCREMENT"] == "YES",
                        is_primarykey = it["KEY_SEQ"] != null
                    )
                }

                TableSchemaRow(
                    tableName =  if (schema != null) "$schema.$tableName" else tableName,
                    tableType = if (records.any { it["TABLE_TYPE"] == "u" }) TableType.TABLE else TableType.VIEW,
                    description = null,
                    columns = columns,
                    pks = records
                        .filter { it["COLUMN_NAME"] != null && it["KEY_SEQ"] != null }
                        .map { it["COLUMN_NAME"] as String },
                    fks = null
                )
            }
        }.flatten()

        return ConnectorConfiguration(
            jdbcUrl = jdbcUrl,
            jdbcProperties = emptyMap(),
            tables = tables,
            functions = emptyList()
        )
    }
}