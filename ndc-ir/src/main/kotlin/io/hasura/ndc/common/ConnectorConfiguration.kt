package io.hasura.ndc.common

data class ConnectorConfiguration(
    val jdbcUrl: String,
    val jdbcProperties: Map<String, Any> = emptyMap(),
    val schemas: List<String> = emptyList(),
    val tables: List<TableSchemaRow> = emptyList(),
    val functions: List<FunctionSchemaRow> = emptyList(),
    val nativeQueries: Map<String, NativeQueryInfo> = emptyMap()
)