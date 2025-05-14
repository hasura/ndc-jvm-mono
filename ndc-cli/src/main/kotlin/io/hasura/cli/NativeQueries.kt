package io.hasura.cli

import com.jakewharton.picnic.BorderStyle
import com.jakewharton.picnic.TextAlignment
import com.jakewharton.picnic.table
import org.jooq.impl.DSL
import java.io.File
import java.sql.ParameterMetaData
import java.sql.ResultSetMetaData
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.NativeQuerySql
import io.hasura.ndc.common.NativeQueryInfo
import io.hasura.ndc.ir.ArgumentInfo
import io.hasura.ndc.ir.Type

const val DEFAULT_SQL_TYPE="VARCHAR"

fun readNativeQuerySQL(
    configurationDir: String,
    nativeQuerySQLFile: String,
): String =
    try {
        File(configurationDir, nativeQuerySQLFile).readText()
    } catch (e: Exception) {
        println("Error reading the native query SQL file: $nativeQuerySQLFile: ${e.message}")
        System.exit(1)
        throw IllegalStateException()
    }

/** Converts a java.sql.Types integer code to a human-readable string representation */
fun getJavaSQLTypeName(sqlType: Int): String =
    when (sqlType) {
        java.sql.Types.ARRAY -> "array"
        java.sql.Types.BIGINT -> "bigint"
        java.sql.Types.BINARY -> "binary"
        java.sql.Types.BIT -> "bit"
        java.sql.Types.BLOB -> "blob"
        java.sql.Types.BOOLEAN -> "boolean"
        java.sql.Types.CHAR -> "char"
        java.sql.Types.CLOB -> "clob"
        java.sql.Types.DATALINK -> "datalink"
        java.sql.Types.DATE -> "date"
        java.sql.Types.DECIMAL -> "decimal"
        java.sql.Types.DISTINCT -> "distinct"
        java.sql.Types.DOUBLE -> "double"
        java.sql.Types.FLOAT -> "float"
        java.sql.Types.INTEGER -> "integer"
        java.sql.Types.JAVA_OBJECT -> "java_object"
        java.sql.Types.LONGNVARCHAR -> "longnvarchar"
        java.sql.Types.LONGVARBINARY -> "longvarbinary"
        java.sql.Types.LONGVARCHAR -> "longvarchar"
        java.sql.Types.NCHAR -> "nchar"
        java.sql.Types.NCLOB -> "nclob"
        java.sql.Types.NULL -> "null"
        java.sql.Types.NUMERIC -> "numeric"
        java.sql.Types.NVARCHAR -> "nvarchar"
        java.sql.Types.OTHER -> "other"
        java.sql.Types.REAL -> "real"
        java.sql.Types.REF -> "ref"
        java.sql.Types.REF_CURSOR -> "ref_cursor"
        java.sql.Types.ROWID -> "rowid"
        java.sql.Types.SMALLINT -> "smallint"
        java.sql.Types.SQLXML -> "sqlxml"
        java.sql.Types.STRUCT -> "struct"
        java.sql.Types.TIME -> "time"
        java.sql.Types.TIME_WITH_TIMEZONE -> "time_tz"
        java.sql.Types.TIMESTAMP -> "timestamp"
        java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp_tz"
        java.sql.Types.TINYINT -> "tinyint"
        java.sql.Types.VARBINARY -> "varbinary"
        java.sql.Types.VARCHAR -> "varchar"
        else -> "json"
    }

/** Represents the result of parsing a SQL query with parameters */
data class SqlParsingResult(
    val originalSql: String,
    val transformedSql: String,
    // Map of position to parameter name
    val parameterPositions: Map<Int, String>,
)

/**
 * Parses a SQL query with parameters that are enclosed in double curly braces and returns:
 * - The original SQL
 * - A transformed SQL with ? placeholders
 * - A map of positions to parameter names
 */
fun parseSqlWithDoubleCurlyBraceParameters(sql: String): SqlParsingResult {
    // Use regex to find all parameters with pattern {{paramName}}
    // The pattern matches opening double curly braces, followed by word characters, followed by closing double curly braces
    val pattern = Regex("\\{\\{([a-zA-Z0-9_]+)\\}\\}")
    val matches = pattern.findAll(sql)

    // Map to track position -> parameter name
    val positionParamMap = mutableMapOf<Int, String>()

    // Transform SQL and collect parameter positions
    var position = 1 // JDBC parameters are 1-indexed

    // First pass: Collect all parameter positions
    for (match in matches) {
        val paramName = match.groupValues[1]
        positionParamMap[position] = paramName
        position++
    }

    // Second pass: Replace parameters with ? placeholders
    val transformedSql = pattern.replace(sql, "?")

    return SqlParsingResult(
        originalSql = sql,
        transformedSql = transformedSql,
        parameterPositions = positionParamMap,
    )
}

fun prettyPrintSQL(sql: String) {
    println("\n=== SQL Query ===")
    println(sql)
    println("=================\n")
}

fun prettyPrintParameters(paramNames: List<String>) {
    if (paramNames.isNotEmpty()) {
        val paramTable = table {
            header {
                row {
                    cell("=== Named Parameters ===") {
                        columnSpan = 2
                    }
                }
                row("Parameter #", "Name")
            }

            paramNames.forEachIndexed { index, name ->
                row(index + 1, name)
            }
        }
        println(paramTable)
    } else {
        println("\n=== No Named Parameters Found ===\n")
    }
}

fun createNativeQuery(
    connectorConfig: ConnectorConfiguration,
    configurationDir: String,
    nativeQuerySQLFile: String,
    nativeQueryName: String,
    overwrite: Boolean,
    skipParamatersMetadata: Boolean, // Option to skip parameter metadata parsing for databases that don't
    // support it
    skipResultSetMetadata: Boolean, // Option to skip result set metadata parsing for databases that don't
    // support it
): ConnectorConfiguration {

    val originalSql = readNativeQuerySQL(configurationDir, nativeQuerySQLFile)

    val jdbcUrl = connectorConfig.jdbcUrl.resolve()
    val ctx = DSL.using(jdbcUrl)

    val parsedResult = parseSqlWithDoubleCurlyBraceParameters(originalSql)

    var nativeQueryArgs: MutableMap<String, ArgumentInfo> = mutableMapOf()
    var nativeQueryColumns: MutableMap<String, Type> = mutableMapOf()

    ctx.connection { connection ->
        // add a try catch block to handle SQL exceptions
        // and print the error message

        try {
            connection.prepareStatement(parsedResult.transformedSql).use { preparedStatement ->
                println("\n=== SQL found in the file: ===")
                println(parsedResult.originalSql)
                println("================================\n")

                // Handle parameter metadata based on skipParamMetadata flag
                if (skipParamatersMetadata) {
                    // Skip parameter metadata retrieval and use defaults
                    processParametersWithDefaults(parsedResult, nativeQueryArgs)
                } else {
                    // Try to get parameter metadata, fall back to defaults if it fails
                    try {
                        val paramMetadata = preparedStatement.parameterMetaData
                        processParameterMetadata(paramMetadata, parsedResult, nativeQueryArgs)
                    } catch (e: Exception) {
                        println("Error getting parameter metadata: ${e.message}")
                        println("Falling back to parsed parameters")
                        processParametersWithDefaults(parsedResult, nativeQueryArgs)
                    }
                }

                if (skipResultSetMetadata) {
                    // Add warning messages for the skipped metadata and that the
                    // user will have to manually enter the column types
                    println("⚠️  WARNING: Result set metadata not available")
                    println("⚠️  You may need to add the metadata about the columns manually \n")
                } else {
                    // Result set metadata handling (unchanged)
                    val resultSetMetaData = preparedStatement.metaData
                    if (resultSetMetaData != null) {
                        val columnCount = resultSetMetaData.columnCount
                        println("Query returns the following $columnCount columns:")

                        // Create a table using Picnic
                        val columnsTable = table {
                            cellStyle {
                                border = true
                                paddingLeft = 1
                                paddingRight = 1
                            }

                            header {
                                row("Column Name", "Type Name", "SQL Type", "Nullable", "Class name")
                            }

                            for (i in 1..columnCount) {
                                val columnName = resultSetMetaData.getColumnName(i)
                                val columnTypeName = resultSetMetaData.getColumnTypeName(i)
                                val columnClassName = resultSetMetaData.getColumnClassName(i)
                                val columnType = getJavaSQLTypeName(resultSetMetaData.getColumnType(i))
                                val columnNullable =
                                    when (resultSetMetaData.isNullable(i)) {
                                        ResultSetMetaData.columnNoNulls -> false
                                        ResultSetMetaData.columnNullable -> true
                                        ResultSetMetaData.columnNullableUnknown -> true
                                        else -> true
                                    }

                                row(columnName, columnTypeName, columnType, columnNullable.toString(), columnClassName)

                                val nativeOperationColumn =
                                    if (columnNullable) {
                                        Type.Nullable(Type.Named(name = columnTypeName))
                                    } else {
                                        Type.Named(name = columnTypeName)
                                    }

                                nativeQueryColumns[columnName] = nativeOperationColumn
                            }
                        }

                        println(columnsTable)
                    } else {
                        println("Unable to retrieve result set metadata for this query")
                    }
                }
            }
        } catch (e: Exception) {
            println("Error preparing the SQL statement: $e")
            System.exit(1)
        }

        val sql = NativeQuerySql.FromFile(nativeQuerySQLFile)

        // Register the native query (unchanged)
        if (nativeQueryName in connectorConfig.nativeQueries) {
            if (overwrite) {
                connectorConfig.nativeQueries[nativeQueryName] =
                    NativeQueryInfo(
                        sql = NativeQuerySql.FromFile(nativeQuerySQLFile),
                        arguments = nativeQueryArgs,
                        columns = nativeQueryColumns,
                    )
            } else {
                println(
                    "Native query with name $nativeQueryName already exists. To overwrite, please run the command with --overwrite",
                )
                System.exit(1)
            }
        } else {
            connectorConfig.nativeQueries[nativeQueryName] =
                NativeQueryInfo(
                    sql = sql,
                    arguments = nativeQueryArgs,
                    columns = nativeQueryColumns,
                )
        }
    }
    return connectorConfig
}

/** Process parameters using metadata from the JDBC driver */
private fun processParameterMetadata(
    paramMetadata: ParameterMetaData,
    parsedResult: SqlParsingResult,
    nativeQueryArgs: MutableMap<String, ArgumentInfo>,
) {
    val paramCount = paramMetadata.parameterCount
    println("Query has $paramCount parameters:")

    if (paramCount > 0) {
        try {
            // Test if we can access the metadata properly by trying to get the first parameter's type name
            // This will throw an exception if the driver doesn't support parameter metadata properly
            paramMetadata.getParameterTypeName(1)

            // Create a table using Picnic
            val paramTable = table {
                cellStyle {
                    border = true
                    paddingLeft = 1
                    paddingRight = 1
                }

                header {
                    row("Param #", "Parameter Name", "SQL Type", "Nullable", "Type Name", "Class Name")
                }

                for (i in 1..paramCount) {
                    try {
                        val jdbcTypeName = paramMetadata.getParameterTypeName(i)
                        val sqlType = getJavaSQLTypeName(paramMetadata.getParameterType(i))
                        val paramClassName = paramMetadata.getParameterClassName(i)
                        val isNullable = when (paramMetadata.isNullable(i)) {
                            ParameterMetaData.parameterNoNulls -> false
                            ParameterMetaData.parameterNullable -> true
                            ParameterMetaData.parameterNullableUnknown -> true
                            else -> true
                        }

                        val paramName = parsedResult.parameterPositions[i]

                        row(i, paramName ?: "", sqlType, isNullable.toString(), jdbcTypeName, paramClassName)

                        val type = if (isNullable) {
                            Type.Nullable(Type.Named(jdbcTypeName))
                        } else {
                            Type.Named(jdbcTypeName)
                        }

                        if (paramName != null) {
                            val nativeOperationArgument = ArgumentInfo(argument_type = type)
                            nativeQueryArgs[paramName] = nativeOperationArgument
                        }
                    } catch (e: Exception) {
                        // If we encounter an exception for a specific parameter, fall back to defaults for this parameter
                        println("Error getting metadata for parameter $i: ${e.message}")
                        println("Falling back to default for this parameter")

                        val paramName = parsedResult.parameterPositions[i]
                        if (paramName != null) {
                            row(i, paramName, "$DEFAULT_SQL_TYPE (DEFAULT)", "true", "$DEFAULT_SQL_TYPE (DEFAULT)", "java.lang.String")

                            val type =
                                Type.Nullable(
                                    underlying_type =
                                        Type.Named(name = DEFAULT_SQL_TYPE))
                            val nativeOperationArgument = ArgumentInfo(
                                argument_type = type,
                                description = "Auto-detected parameter, type defaulted to varchar"
                            )
                            nativeQueryArgs[paramName] = nativeOperationArgument
                        }
                    }
                }
            }

            println(paramTable)
        } catch (e: Exception) {
            // If we encounter an exception while testing metadata access, fall back to defaults for all parameters
            println("Parameter metadata not supported by this JDBC driver: ${e.message}")
            println("Falling back to defaults for all parameters")
            processParametersWithDefaults(parsedResult, nativeQueryArgs)
        }
    }
}

/** Process parameters with default values when metadata is not available */
private fun processParametersWithDefaults(
    parsedResult: SqlParsingResult,
    nativeQueryArgs: MutableMap<String, ArgumentInfo>,
) {
    println("Query has ${parsedResult.parameterPositions.size} parameters:")
    println("\n⚠️  WARNING: Parameter metadata not available or skipped")
    println("⚠️  Defaulting all parameters to $DEFAULT_SQL_TYPE type")
    println("⚠️  You may need to manually correct the parameter types after generation\n")

    if (parsedResult.parameterPositions.isNotEmpty()) {
        // Create a table using Picnic
        val defaultParamTable = table {

            cellStyle {
                border = true
                paddingLeft = 1
                paddingRight = 1
            }

            header {
                row("Param #", "Parameter Name", "SQL Type", "Class Name", "Nullable", "Type Name")
            }

            parsedResult.parameterPositions.forEach { (position, paramName) ->
                row(
                    position,
                    paramName,
                    "$DEFAULT_SQL_TYPE (DEFAULT)",
                    "java.lang.String",
                    "true",
                    "$DEFAULT_SQL_TYPE (DEFAULT)"
                )

                val type = Type.Nullable (underlying_type = Type.Named(name = DEFAULT_SQL_TYPE))

                val nativeOperationArgument =
                    ArgumentInfo(
                        argument_type = type,
                        description = "Auto-detected parameter, type defaulted to varchar",
                    )
                nativeQueryArgs[paramName] = nativeOperationArgument
            }
        }

        println(defaultParamTable)
    }
}
