package io.hasura.phoenix

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.hasura.ndc.app.interfaces.IDataSourceProvider
import io.hasura.ndc.app.services.dataConnectors.BaseDataConnectorService
import io.opentelemetry.api.trace.Tracer
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.UriInfo
import io.hasura.ndc.ir.*
import io.hasura.ndc.sqlgen.MutationTranslator
import jakarta.annotation.Priority
import jakarta.enterprise.inject.Alternative
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderOptionalKeyword
import org.jooq.conf.RenderQuotedNames


class Filters {

    @ServerRequestFilter(priority = 0)
    fun logBodyFilter(info: UriInfo, request: HttpServerRequest, ctx: ContainerRequestContext) {
        request.body {
            val text = it.result().toString()
            // Print JSON string formatted with Jackson
            val json = jacksonObjectMapper().readValue<Any>(text)
            println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json))
        }
    }
}

@Singleton
@Alternative
@Priority(1)
class PhoenixDataConnectorService @Inject constructor(
    tracer: Tracer,
    datasourceProvider: IDataSourceProvider
) : BaseDataConnectorService(
    tracer,
    PhoenixJDBCSchemaGenerator,
    datasourceProvider
) {

    override val capabilitiesResponse = CapabilitiesResponse(
        version = "0.1.2",
        capabilities = Capabilities(
            query = QueryCapabilities(
                aggregates = mapOf(),
                variables = mapOf(),
                explain = mapOf()
            ),
            mutation = MutationCapabilities(),
            relationships = RelationshipsCapabilities(
                relation_comparisons = mapOf(),
                order_by_aggregate = mapOf()
            )
        )
    )

    override fun handleQuery(request: QueryRequest): List<RowSet> {
        val dslCtx = mkDSLCtx()

        val query = NoRelationshipsQueryGenerator.queryRequestToSQL(request)

        println(
            dslCtx.renderInlined(query)
        )

        val rows = dslCtx.fetch(query).intoMaps()

        val rowset = when {
            !request.query.aggregates.isNullOrEmpty() -> RowSet(aggregates = rows.first())
            !request.query.fields.isNullOrEmpty() -> RowSet(rows = rows)
            else -> RowSet()
        }

        return listOf(rowset)
    }

    override val jooqDialect = SQLDialect.POSTGRES
    override val jooqSettings =
        commonDSLContextSettings
            .withRenderQuotedNames(RenderQuotedNames.ALWAYS)
            .withRenderOptionalAsKeywordForFieldAliases(RenderOptionalKeyword.ON)

    override val sqlGenerator = NoRelationshipsQueryGenerator
    override val mutationTranslator = MutationTranslator
}
