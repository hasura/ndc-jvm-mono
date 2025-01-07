package io.hasura.cli

import io.hasura.ndc.common.ColumnSchemaRow
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.TableSchemaRow
import io.hasura.ndc.common.TableType
import org.jooq.impl.DSL
import java.sql.JDBCType
import java.sql.Types

object PhoenixConfigGenerator : IConfigGenerator {


    private fun translatePhoenixDataTypeToSqlType(phoenixDataType: Int?, isThinClient: Boolean = true): String {

        val sqlType = if (!isThinClient) {
            phoenixDataType ?: Types.OTHER
        } else {
            when (phoenixDataType) {
                null -> Types.OTHER           // Handle null data_type
                -6 -> Types.TINYINT           // TINYINT
                -5 -> Types.BIGINT            // BIGINT
                -3 -> Types.VARBINARY         // VARBINARY
                -2 -> Types.BINARY            // BINARY
                1 -> Types.CHAR               // CHAR
                3 -> Types.DECIMAL            // DECIMAL
                4 -> Types.INTEGER            // INTEGER
                5 -> Types.SMALLINT           // SMALLINT
                6 -> Types.FLOAT              // FLOAT
                8 -> Types.DOUBLE             // DOUBLE
                9 -> Types.VARCHAR            // VARCHAR
                10 -> Types.SMALLINT          // UNSIGNED_SMALLINT (maps to SMALLINT)
                11 -> Types.FLOAT             // UNSIGNED_FLOAT (maps to FLOAT)
                12 -> Types.VARCHAR           // VARCHAR (Phoenix specific)
                13 -> Types.VARCHAR           // (Custom/Unsupported, mapped to VARCHAR)
                14 -> Types.VARCHAR           // (Custom/Unsupported, mapped to VARCHAR)
                15 -> Types.VARCHAR           // (Custom/Unsupported, mapped to VARCHAR)
                16 -> Types.BOOLEAN           // BOOLEAN
                18 -> Types.ARRAY             // ARRAY
                19 -> Types.VARBINARY         // VARBINARY (Phoenix specific)
                20 -> Types.VARBINARY         // VARBINARY (Phoenix specific)
                91 -> Types.DATE              // DATE
                92 -> Types.TIME              // TIME
                93 -> Types.TIMESTAMP         // TIMESTAMP
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

    override fun getConfig(
        jdbcUrl: String,
        schemas: List<String>
    ): ConnectorConfiguration {
        val ctx = DSL.using(jdbcUrl)

        val isThinClient = jdbcUrl.contains("phoenix:thin", ignoreCase = true)

        val stmt = when {
            schemas.isNotEmpty() -> "SELECT * FROM SYSTEM.CATALOG WHERE TABLE_SCHEM IN (${schemas.joinToString { "'$it'" } })"
            else -> "SELECT * FROM SYSTEM.CATALOG WHERE TABLE_SCHEM != 'SYSTEM' OR TABLE_SCHEM IS NULL"
        }
        
        val result = ctx.fetch(stmt)

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
                        type = translatePhoenixDataTypeToSqlType(it["DATA_TYPE"] as? Int, isThinClient),
                        numeric_scale = null,
                        nullable = it["NULLABLE"] == 1,
                        auto_increment = it["IS_AUTOINCREMENT"] == "YES",
                        is_primarykey = it["KEY_SEQ"] != null
                    )
                }

                TableSchemaRow(
                    tableName = if (schema != null) "$schema.$tableName" else tableName,
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
