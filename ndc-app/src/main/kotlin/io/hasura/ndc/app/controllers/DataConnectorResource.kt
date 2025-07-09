package io.hasura.ndc.app.controllers

import io.hasura.ndc.app.interfaces.IDataConnectorService
import io.hasura.ndc.app.models.ExplainResponse
import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import io.hasura.ndc.ir.*

@Path("")
class DataConnectorResource @Inject constructor(
    val dataConnectorService: IDataConnectorService
) {

    @GET
    @Path("/health")
    @WithSpan
    fun health(): Response {
        // Phil: "I think we decided /health should only indicate readiness to serve e.g. /schema and /capabilities,
        // and should not require the DB to be available, when we worked on introspection."
        return try {
            dataConnectorService.getSchema()
            dataConnectorService.getCapabilities()
            Response.status(Response.Status.OK).build()
        } catch (e: Exception) {
            Response.status(Response.Status.SERVICE_UNAVAILABLE).build()
        }
    }

    @GET
    @Path("/capabilities")
    @WithSpan
    fun getCapabilities(): CapabilitiesResponse {
        return dataConnectorService.getCapabilities()
    }

    @GET
    @Path("/schema")
    @WithSpan
    fun getSchema(): SchemaResponse {
        return dataConnectorService.getSchema()
    }

    @POST
    @Path("/query")
    @WithSpan
    fun handleQuery(query: QueryRequest): List<RowSet> {
        return dataConnectorService.handleQuery(query)
    }

    @POST
    @Path("/mutation")
    @WithSpan
    fun handleMutation(mutation: MutationRequest): MutationResponse {
        return dataConnectorService.handleMutation(mutation)
    }

    @POST
    @Path("/explain")
    @WithSpan
    fun explainQuery(query: QueryRequest): ExplainResponse {
        return dataConnectorService.explainQuery(query)
    }

}

