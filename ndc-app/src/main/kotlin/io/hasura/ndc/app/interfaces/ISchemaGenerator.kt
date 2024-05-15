package io.hasura.ndc.app.interfaces

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.ir.SchemaResponse

interface ISchemaGenerator {
    fun getSchema(config: ConnectorConfiguration): SchemaResponse
}