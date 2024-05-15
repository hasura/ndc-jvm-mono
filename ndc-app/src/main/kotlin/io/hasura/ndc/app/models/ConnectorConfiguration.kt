package io.hasura.ndc.app.models

import io.hasura.ndc.common.FunctionSchemaRow
import io.hasura.ndc.common.NativeQueryInfo
import io.hasura.ndc.common.TableSchemaRow

data class ConnectorConfiguration(
    val jdbcUrl: String,
    val jdbcProperties: Map<String, Any> = emptyMap(),
    val schemas: List<String> = emptyList(),
    val tables: List<TableSchemaRow> = emptyList(),
    val functions: List<FunctionSchemaRow> = emptyList(),
    val nativeQueries: Map<String, NativeQueryInfo> = emptyMap()
)
