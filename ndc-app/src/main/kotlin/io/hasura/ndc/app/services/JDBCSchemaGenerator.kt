package io.hasura.ndc.app.services

import io.hasura.ndc.app.interfaces.ISchemaGenerator
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.*
import io.hasura.ndc.ir.*

abstract class JDBCSchemaGenerator(
    private val supportsMutations: Boolean = false
) : ISchemaGenerator {

    abstract fun getScalars(): Map<String, ScalarType>

    abstract fun mapScalarType(
        columnTypeStr: String,
        numericPrecision: Int?,
        numericScale: Int?
    ): NDCScalar

    private fun mapTableSchemaRowToCollectionInfo(table: TableSchemaRow): CollectionInfo {
        fun mapColumn(column: ColumnSchemaRow, tableType: TableType): String? {
            val insertable = supportsMutations && tableType != TableType.VIEW && !column.auto_increment
            return if (!insertable) null else column.name
        }

        val mutableCols = table.columns
            .mapNotNull { mapColumn(it, table.tableType) }
            .minus(table.pks ?: emptyList())

        return CollectionInfo(
            name = table.tableName,
            description = table.description,
            arguments = emptyMap(),
            type = table.tableName,
            insertable_columns = mutableCols,
            updatable_columns = mutableCols,
            deletable = true,
            uniqueness_constraints = if (table.pks == null)
                emptyMap()
            else
                mapOf("${table.tableName}_PK" to UniquenessConstraint(table.pks!!)),
            foreign_keys = table.fks ?: emptyMap(),
        )
    }

    private fun mapTableSchemaRowToObjectType(table: TableSchemaRow): Pair<String, ObjectType> {
        return table.tableName to ObjectType(
            description = table.description,
            fields = table.columns.associate {
                it.name to ObjectField(
                    description = it.description,
                    arguments = emptyMap(),
                    type = when (it.nullable) {
                        true -> Type.Nullable(Type.Named(mapScalarType(it.type, it.numeric_precision, it.numeric_scale).name))
                        false -> Type.Named(mapScalarType(it.type, it.numeric_precision, it.numeric_scale).name)
                    }
                )
            }
        )
    }

    private fun mapNativeQueryToCollectionInfo(name: String, nativeQuery: NativeQueryInfo): CollectionInfo {
        return CollectionInfo(
            name = name,
            type = name,
            arguments = nativeQuery.arguments,
            deletable = false,
            uniqueness_constraints = emptyMap(),
            foreign_keys = emptyMap()
        )
    }

    private fun mapNativeQueryToObjectType(name: String, nativeQuery: NativeQueryInfo): Pair<String, ObjectType> {
        return name to ObjectType(
            fields = nativeQuery.columns.map { (colName, type) ->
                colName to ObjectField(
                    arguments = emptyMap(),
                    type = type
                )
            }.toMap()
        )
    }

    private fun mapFunctionSchemaRowToFunctionInfo(function: FunctionSchemaRow): FunctionInfo {
        return FunctionInfo(
            name = function.function_name,
            description = function.comment,
            arguments = emptyMap(),
            result_type = Type.Named(mapScalarType(function.data_type, null, null).name)
        )
    }


    private fun buildSchema(
        tables: List<TableSchemaRow>,
        functions: List<FunctionSchemaRow>,
        nativeQueries: Map<String, NativeQueryInfo>
    ): SchemaResponse {
        return SchemaResponse(
            scalar_types = getScalars(),
            object_types = tables.associate { mapTableSchemaRowToObjectType(it) }
                + nativeQueries.map { mapNativeQueryToObjectType(it.key, it.value) }.toMap(),
            collections = tables.map { mapTableSchemaRowToCollectionInfo(it) }
                + nativeQueries.map { mapNativeQueryToCollectionInfo(it.key, it.value) },
            functions = functions.map { mapFunctionSchemaRowToFunctionInfo(it) },
            procedures = emptyList()
        )
    }

    override fun getSchema(config: ConnectorConfiguration): SchemaResponse {
        return buildSchema(config.tables, config.functions, config.nativeQueries)
    }

    fun mapNumericPrecisionAndScaleToNDCScalar(
        precision: Int,
        scale: Int
    ): NDCScalar {
        return when {
            scale != 0 -> when {
                // FLOAT32: Up to 7 digits (values from -3.4028235E+38 to 3.4028235E+38).
                precision <= 7 -> NDCScalar.FLOAT32
                // FLOAT64: Up to 15 digits (values from -1.7976931348623157E+308 to 1.7976931348623157E+308).
                precision <= 15 -> NDCScalar.FLOAT64
                // BIGDECIMAL: More than 15 digits.
                else -> NDCScalar.BIGDECIMAL
            }
            // INT8: Up to 3 digits (values from -128 to 127).
            precision <= 3 -> NDCScalar.INT8
            // INT16: Up to 5 digits (values from -32,768 to 32,767).
            precision <= 5 -> NDCScalar.INT16
            // INT32: Up to 10 digits (values from -2,147,483,648 to 2,147,483,647).
            precision <= 10 -> NDCScalar.INT32
            // INT64: Up to 19 digits (values from -9,223,372,036,854,775,808 to 9,223,372,036,854,775,807).
            precision <= 19 -> NDCScalar.INT64
            // BIGINTEGER: More than 19 digits.
            else -> NDCScalar.BIGINTEGER
        }
    }
}
