package io.hasura.ndc.app.interfaces

import io.hasura.ndc.app.models.ExplainResponse
import io.hasura.ndc.ir.*

interface IDataConnectorService {

    // The /capabilities endpoint
    fun getCapabilities(): CapabilitiesResponse

    // The /schema endpoint
    fun getSchema(): SchemaResponse

    // The /explain endpoint
    fun explainQuery(
        request: QueryRequest
    ): ExplainResponse

    // The /query endpoint
    fun handleQuery(
        request: QueryRequest
    ): List<RowSet>

    // The /mutation endpoint
    fun handleMutation(
        request: MutationRequest
    ): MutationResponse {
        return MutationResponse()
    }

    // The /health endpoint
    // Method used in healthcheck, a SELECT 1=1 query
    fun runHealthCheckQuery(): Boolean

}
