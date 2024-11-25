package io.hasura.cli

import io.hasura.ndc.common.ColumnSchemaRow
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import org.jooq.impl.DSL
import java.sql.JDBCType
import java.sql.Types

object PhoenixConfigGenerator : IConfigGenerator {


    private fun translatePhoenixDataTypeToSqlType(phoenixDataType: Int, isThinClient: Boolean = true): String {

        val sqlType = if (!isThinClient) {
            phoenixDataType
        } else {
            when (phoenixDataType) {
                0 -> Types.TINYINT          // UNSIGNED_TINYINT
                1 -> Types.SMALLINT         // UNSIGNED_SMALLINT
                2 -> Types.INTEGER          // UNSIGNED_INT
                3 -> Types.BIGINT           // UNSIGNED_LONG
                4 -> Types.FLOAT            // UNSIGNED_FLOAT
                5 -> Types.DOUBLE           // UNSIGNED_DOUBLE
                6 -> Types.BINARY           // BINARY
                7 -> Types.CHAR             // CHAR
                8 -> Types.VARCHAR          // VARCHAR
                9 -> Types.VARBINARY        // VARBINARY
                10 -> Types.DECIMAL         // DECIMAL
                11 -> Types.TIMESTAMP       // TIMESTAMP
                12 -> Types.DATE            // DATE
                13 -> Types.TIME            // TIME
                14 -> Types.TIME            // UNSIGNED_TIME
                15 -> Types.DATE            // UNSIGNED_DATE
                16 -> Types.TIMESTAMP       // UNSIGNED_TIMESTAMP
                17 -> Types.ARRAY           // ARRAY
                18 -> Types.BOOLEAN         // BOOLEAN
                19 -> Types.TINYINT         // TINYINT
                20 -> Types.SMALLINT        // SMALLINT
                21 -> Types.INTEGER         // INTEGER
                22 -> Types.BIGINT          // BIGINT
                23 -> Types.FLOAT           // FLOAT
                24 -> Types.DOUBLE          // DOUBLE
                25 -> Types.ARRAY           // UNSIGNED_ARRAY
                26 -> Types.BINARY          // UUID
                else ->
                    if (JDBCType.valueOf(phoenixDataType) != null) {
                        phoenixDataType
                    } else {
                        throw IllegalArgumentException("Unknown Phoenix data type: $phoenixDataType")
                    }
            }
        }
        return JDBCType.valueOf(sqlType).name
    }

    fun main() {
        // Example usage:
        val phoenixDataType = 8 // Example: VARCHAR
        val sqlType = translatePhoenixDataTypeToSqlType(phoenixDataType)

        println("Phoenix Data Type: $phoenixDataType")
        println("Java SQL Type: $sqlType") // Should output: 12 (VARCHAR)
    }


    override fun getConfig(
        jdbcUrl: String,
        schemas: List<String>
    ): ConnectorConfiguration {
        val ctx = DSL.using(jdbcUrl)

        val isThinClient = jdbcUrl.contains("phoenix:thin", ignoreCase = true)

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
                        type = translatePhoenixDataTypeToSqlType(it["DATA_TYPE"] as Int, isThinClient),
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
