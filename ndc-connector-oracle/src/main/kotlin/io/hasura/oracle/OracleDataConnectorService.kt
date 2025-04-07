package io.hasura.oracle

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
class OracleDataConnectorService @Inject constructor(
    tracer: Tracer,
    datasourceProvider: IDataSourceProvider
) : BaseDataConnectorService(
    tracer,
    OracleJDBCSchemaGenerator,
    datasourceProvider
) {

    override val capabilitiesResponse = CapabilitiesResponse(
        version = "0.1.6",
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
        val query = JsonQueryGenerator.queryRequestToSQL(request)

        println(dslCtx.renderInlined(query))

        val rows = executeDbQuery(query, dslCtx)
        val json = rows.getValue(0, 0).toString()
        val rowset = objectMapper.readValue<List<RowSet>?>(json)

        return if (rowset.isNullOrEmpty()) {
            listOf(RowSet(rows = emptyList(), aggregates = emptyMap()))
        } else {
            rowset
        }
    }

    override val jooqDialect = SQLDialect.ORACLE21C
    override val jooqSettings =
        commonDSLContextSettings.withRenderQuotedNames(RenderQuotedNames.EXPLICIT_DEFAULT_UNQUOTED)
    override val sqlGenerator = JsonQueryGenerator
    override val mutationTranslator = MutationTranslator
}
