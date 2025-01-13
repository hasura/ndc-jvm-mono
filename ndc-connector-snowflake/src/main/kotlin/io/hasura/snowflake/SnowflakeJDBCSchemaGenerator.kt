package io.hasura.snowflake

import io.hasura.ndc.app.services.JDBCSchemaGenerator
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.common.javaSqlTypeToNDCScalar
import io.hasura.ndc.ir.AggregateFunctionDefinition
import io.hasura.ndc.ir.ComparisonOperatorDefinition
import io.hasura.ndc.ir.ScalarRepresentation
import io.hasura.ndc.ir.ScalarType
import io.hasura.ndc.ir.Type
import net.snowflake.client.jdbc.SnowflakeType

object SnowflakeJDBCSchemaGenerator : JDBCSchemaGenerator() {

    override fun getScalars(): Map<String, ScalarType> {
        return mapOf(
            NDCScalar.BOOLEAN.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.BOOLEAN),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.STRING.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.STRING),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_contains" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.STRING.name)),
                    "_like" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.STRING.name)),
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.STRING.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.STRING.name))
                )
            ),
            NDCScalar.INT8.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.INT8),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT8.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT8.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT8.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT8.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT8.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT8.name))
                )
            ),
            NDCScalar.INT16.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.INT16),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT16.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT16.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT16.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT16.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT16.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT16.name))
                )
            ),
            NDCScalar.INT32.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.INT32),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT32.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT32.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT32.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT32.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT32.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT32.name))
                )
            ),
            NDCScalar.INT64.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.INT64),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT64.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT64.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT64.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT64.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name))
                )
            ),
            NDCScalar.FLOAT32.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.FLOAT32),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT32.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT32.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT32.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT32.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT32.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT32.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT32.name))
                )
            ),
            NDCScalar.FLOAT64.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.FLOAT64),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
                )
            ),
            NDCScalar.BIGINTEGER.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.BIGINTEGER),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGINTEGER.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGINTEGER.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGINTEGER.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGINTEGER.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGINTEGER.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGINTEGER.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGINTEGER.name))
                )
            ),
            NDCScalar.BIGDECIMAL.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.BIGDECIMAL),
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGDECIMAL.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGDECIMAL.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGDECIMAL.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.BIGDECIMAL.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGDECIMAL.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGDECIMAL.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGDECIMAL.name))
                )
            ),
            NDCScalar.UUID.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.UUID),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.DATE.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.DATE),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.TIMESTAMP.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.TIMESTAMP),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMP.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMP.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMP.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMP.name)),
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.TIMESTAMPTZ.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.TIMESTAMPTZ),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMPTZ.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMPTZ.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMPTZ.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIMESTAMPTZ.name)),
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.GEOGRAPHY.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.GEOGRAPHY),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.GEOMETRY.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.GEOMETRY),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.BYTES.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.BYTES),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.JSON.name to ScalarType(
                representation = ScalarRepresentation(NDCScalar.JSON),
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal,
                ),
                aggregate_functions = emptyMap()
            )
        )
    }

    override fun mapScalarType(columnTypeStr: String, numericPrecision: Int?, numericScale: Int?): NDCScalar {
        return when (columnTypeStr.uppercase()) {
            // Numeric Types
            "TINYINT" -> NDCScalar.INT8
            "SMALLINT" -> NDCScalar.INT16
            "INT", "INTEGER" -> NDCScalar.INT32
            "BIGINT" -> NDCScalar.INT64

            // Floating-Point Types
            "FLOAT", "FLOAT4", "FLOAT8", "REAL" -> {
                if (numericPrecision != null && numericPrecision <= 24) NDCScalar.FLOAT32 else NDCScalar.FLOAT64
            }
            "DOUBLE", "DOUBLE PRECISION" -> NDCScalar.FLOAT64

            // Numeric and Decimal Types
            "NUMBER", "NUMERIC", "DECIMAL" -> {
                when {
                    numericScale == 0 -> when {
                        numericPrecision == null -> NDCScalar.BIGINTEGER
                        numericPrecision <= 2 -> NDCScalar.INT8
                        numericPrecision <= 4 -> NDCScalar.INT16
                        numericPrecision <= 9 -> NDCScalar.INT32
                        numericPrecision <= 18 -> NDCScalar.INT64
                        else -> NDCScalar.BIGINTEGER
                    }
                    numericScale != null && numericScale > 0 -> when {
                        numericPrecision == null -> NDCScalar.BIGDECIMAL
                        numericPrecision <= 7 -> NDCScalar.FLOAT32
                        numericPrecision <= 15 -> NDCScalar.FLOAT64
                        else -> NDCScalar.BIGDECIMAL
                    }
                    else -> NDCScalar.BIGDECIMAL
                }
            }
    
            // String Types
            "CHAR" -> NDCScalar.STRING
            "VARCHAR" -> NDCScalar.STRING
            "STRING" -> NDCScalar.STRING
            "TEXT" -> NDCScalar.STRING
    
            // Binary Types
            "BINARY" -> NDCScalar.BYTES
            "VARBINARY" -> NDCScalar.BYTES
    
            // Date and Time Types
            "DATE" -> NDCScalar.DATE
            "TIME" -> NDCScalar.TIMESTAMP
            "TIMESTAMP_NTZ" -> NDCScalar.TIMESTAMP
            "TIMESTAMP_LTZ" -> NDCScalar.TIMESTAMPTZ
            "TIMESTAMP_TZ" -> NDCScalar.TIMESTAMPTZ
    
            // Semi-structured Data Types
            "VARIANT" -> NDCScalar.JSON
            "OBJECT" -> NDCScalar.JSON
            "ARRAY" -> NDCScalar.JSON
    
            // Geography and Geometry Types
            "GEOGRAPHY" -> NDCScalar.GEOGRAPHY
            "GEOMETRY" -> NDCScalar.GEOMETRY
    
            // Boolean Type
            "BOOLEAN" -> NDCScalar.BOOLEAN
    
            // Default Fallback
            else -> NDCScalar.JSON
        }
    }
}
