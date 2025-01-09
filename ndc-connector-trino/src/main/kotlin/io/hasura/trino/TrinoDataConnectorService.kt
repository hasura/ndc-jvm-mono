package io.hasura.trino

import com.fasterxml.jackson.module.kotlin.readValue
import io.hasura.ndc.app.interfaces.IDataSourceProvider
import io.hasura.ndc.app.services.dataConnectors.BaseDataConnectorService
import io.hasura.ndc.ir.*
import io.hasura.ndc.sqlgen.MutationTranslator
import io.opentelemetry.api.trace.Tracer
import jakarta.annotation.Priority
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames

@Singleton
@Alternative
@Priority(1)
class TrinoDataConnectorService @Inject constructor(
    tracer: Tracer,
    datasourceProvider: IDataSourceProvider
) : BaseDataConnectorService(
    tracer,
    TrinoJDBCSchemaGenerator,
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

        val query = if (!request.variables.isNullOrEmpty()) {
            CTEQueryGenerator.forEachQueryRequestToSQL(request)
        } else {
            CTEQueryGenerator.queryRequestToSQL(request)
        }

        val rows = executeDbQuery(query, dslCtx)
        val json = rows.getValue(0, 0).toString()

        val rowsets = objectMapper.readValue<List<RowSet>>(json)
        return rowsets
    }

    override val jooqDialect = SQLDialect.TRINO
    override val jooqSettings =
        commonDSLContextSettings.withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_UNQUOTED)
    override val sqlGenerator = CTEQueryGenerator
    override val mutationTranslator = MutationTranslator
}
