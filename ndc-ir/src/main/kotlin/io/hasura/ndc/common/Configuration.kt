package io.hasura.ndc.common

import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.hasura.ndc.ir.SchemaResponse

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
open class Configuration {
    open val schemas: List<String> = emptyList()
    open var schema: SchemaResponse = SchemaResponse(
        scalar_types = emptyMap(),
        object_types = emptyMap(),
        collections = emptyList(),
        functions = emptyList(),
        procedures = emptyList()
    )
    open val nativeQueries: Map<String, NativeQueryInfo> = emptyMap()
}

typealias RawConfiguration = Configuration
