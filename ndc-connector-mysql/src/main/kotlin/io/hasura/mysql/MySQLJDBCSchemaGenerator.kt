package io.hasura.mysql

import io.hasura.ndc.app.services.JDBCSchemaGenerator
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.AggregateFunctionDefinition
import io.hasura.ndc.ir.ComparisonOperatorDefinition
import io.hasura.ndc.ir.ScalarType
import io.hasura.ndc.ir.Type

object MySQLJDBCSchemaGenerator : JDBCSchemaGenerator() {

    override fun getScalars(): Map<String, ScalarType> {
        return mapOf(
            NDCScalar.INT.name to ScalarType(
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.INT.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name))
                )
            ),
            NDCScalar.FLOAT.name to ScalarType(
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.FLOAT.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = mapOf(
                    "avg" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "sum" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "count" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.INT.name)),
                    "min" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "max" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "stddev_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "stddev_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "var_pop" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name)),
                    "var_samp" to AggregateFunctionDefinition(result_type = Type.Named(NDCScalar.FLOAT.name))
                )
            ),
            NDCScalar.BOOLEAN.name to ScalarType(
                comparison_operators = mapOf(
                    "_eq" to ComparisonOperatorDefinition.Equal,
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.STRING.name to ScalarType(
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
            NDCScalar.DATETIME.name to ScalarType(
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATETIME.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATETIME.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATETIME.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATETIME.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.DATE.name to ScalarType(
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.DATE.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = emptyMap()
            ),
            NDCScalar.TIME.name to ScalarType(
                comparison_operators = mapOf(
                    "_gt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIME.name)),
                    "_lt" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIME.name)),
                    "_gte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIME.name)),
                    "_lte" to ComparisonOperatorDefinition.Custom(argument_type = Type.Named(NDCScalar.TIME.name)),
                    "_eq" to ComparisonOperatorDefinition.Equal,
                    "_in" to ComparisonOperatorDefinition.In
                ),
                aggregate_functions = emptyMap()
            ),
        )
    }

    override fun mapScalarType(columnTypeStr: String, numericScale: Int?): NDCScalar {
        return when (columnTypeStr.uppercase()) {
            "INTEGER", "INT", "SMALLINT", "TINYINT", "MEDIUMINT", "BIGINT" -> NDCScalar.INT
            "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE" -> NDCScalar.FLOAT
            "DATE" -> NDCScalar.DATE
            "DATETIME" -> NDCScalar.DATETIME
            "TIME" -> NDCScalar.TIME
            "TIMESTAMP" -> NDCScalar.DATETIME_WITH_TIMEZONE
            else -> NDCScalar.STRING
        }
    }
}
