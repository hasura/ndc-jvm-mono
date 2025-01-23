package io.hasura.trino

import io.hasura.ndc.app.services.JDBCSchemaGenerator
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.AggregateFunctionDefinition
import io.hasura.ndc.ir.ComparisonOperatorDefinition
import io.hasura.ndc.ir.ScalarRepresentation
import io.hasura.ndc.ir.ScalarType
import io.hasura.ndc.ir.Type

object TrinoJDBCSchemaGenerator : JDBCSchemaGenerator() {

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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT8.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT16.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT32.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT64.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT32.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGINTEGER.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.BIGDECIMAL.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT64.name))
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

    override fun mapScalarType(
        columnTypeStr: String,
        numericPrecision: Int?,
        numericScale: Int?
    ): NDCScalar {
        val t = columnTypeStr.lowercase()
        return when {
            t == "boolean" -> NDCScalar.BOOLEAN
            t == "tinyint" -> NDCScalar.INT8
            t == "smallint" -> NDCScalar.INT16
            t == "int" -> NDCScalar.INT32
            t == "integer" -> NDCScalar.INT32
            t == "bigint" -> NDCScalar.INT64
            t == "real" -> NDCScalar.FLOAT32
            t == "double" -> NDCScalar.FLOAT64
            t.startsWith("decimal") -> mapNumericPrecisionAndScaleToNDCScalar(numericPrecision ?: 0, numericScale ?: 0)
            t.startsWith("char") -> NDCScalar.STRING
            t.startsWith("varchar") -> NDCScalar.STRING
            t == "varbinary" -> NDCScalar.BYTES
            t == "json" -> NDCScalar.JSON
            t == "date" -> NDCScalar.DATE
            t.endsWith("with time zone") -> NDCScalar.TIMESTAMPTZ
            t.startsWith("timestamp") -> NDCScalar.TIMESTAMP
            t.startsWith("time") -> NDCScalar.TIMESTAMP
            t.startsWith("interval") -> NDCScalar.STRING
            t == "uuid" -> NDCScalar.UUID
            else -> NDCScalar.STRING
        }
    }


}
