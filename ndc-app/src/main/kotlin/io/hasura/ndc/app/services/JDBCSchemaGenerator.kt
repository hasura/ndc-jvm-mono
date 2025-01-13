package io.hasura.ndc.app.services

import io.hasura.ndc.app.interfaces.ISchemaGenerator
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.*
import io.hasura.ndc.ir.*
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default

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
}
