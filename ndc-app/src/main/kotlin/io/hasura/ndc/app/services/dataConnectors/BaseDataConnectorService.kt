package io.hasura.ndc.app.services.dataConnectors

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import io.hasura.ndc.app.interfaces.IDataConnectorService
import io.hasura.ndc.app.interfaces.IDataSourceProvider
import io.hasura.ndc.app.interfaces.ISchemaGenerator
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.app.models.ExplainResponse
import io.hasura.ndc.app.services.AgroalDataSourceService
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.instrumentation.annotations.WithSpan
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import io.hasura.ndc.ir.*
import io.hasura.ndc.sqlgen.BaseMutationTranslator
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Result
import org.jooq.ResultQuery
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.conf.StatementType
import org.jooq.impl.DSL
import javax.sql.DataSource

@ApplicationScoped
class JDBCDataSourceProvider : IDataSourceProvider {

    @Inject
    private lateinit var agroalDataSourceService: AgroalDataSourceService

    // Cache DataSources by resolved JDBC URL and properties so we can reuse pools per connection_string
    private val dataSources = java.util.concurrent.ConcurrentHashMap<DataSourceKey, DataSource>()

    private data class DataSourceKey(val jdbcUrl: String, val jdbcProperties: Map<String, Any>)

    override fun getDataSource(config: ConnectorConfiguration): DataSource {
        val resolvedUrl = config.jdbcUrl.resolve()
        val props = config.jdbcProperties
        val key = DataSourceKey(resolvedUrl, props)
        return dataSources.computeIfAbsent(key) {
            agroalDataSourceService.createTracingDataSource(
                ConnectorConfiguration(
                    jdbcUrl = io.hasura.ndc.common.JdbcUrlConfig.Literal(resolvedUrl),
                    jdbcProperties = props
                )
            )
        }
    }
}

abstract class BaseDataConnectorService(
    private val tracer: Tracer,
    private val schemaGenerator: ISchemaGenerator,
    private val dataSourceProvider: IDataSourceProvider
) : IDataConnectorService {

    @Inject
    lateinit var objectMapper: ObjectMapper

    abstract val jooqDialect: SQLDialect
    abstract val jooqSettings: Settings
    abstract val sqlGenerator: BaseQueryGenerator
    abstract val mutationTranslator: BaseMutationTranslator
    abstract val capabilitiesResponse: CapabilitiesResponse

    val commonDSLContextSettings: Settings =
        Settings()
            .withRenderFormatted(true)
            .withStatementType(StatementType.STATIC_STATEMENT)

    @WithSpan
    override fun getCapabilities(): CapabilitiesResponse {
        return this.capabilitiesResponse
    }

    @WithSpan
    fun buildQuery(queryRequest: QueryRequest): ResultQuery<*> =
        sqlGenerator.handleRequest(queryRequest)

    @WithSpan
    open fun processQueryDbRows(dbRows: Result<out Record>): List<RowSet> {
        val json = (dbRows.getValue(0, 0).toString())
        val typeRef = object : TypeReference<List<RowSet>>() {}
        return objectMapper.readValue(json, typeRef)
    }

    @WithSpan
    fun executeQuery(query: ResultQuery<*>, ctx: DSLContext): List<RowSet> {
        val rows = executeDbQuery(query, ctx)
        return processQueryDbRows(rows)
    }

    @WithSpan
    open fun executeDbQuery(query: ResultQuery<*>, ctx: DSLContext): Result<out Record> {
        val t = ctx.render(query)
        return ctx.fetch(query)
    }

    protected open fun executeAndSerializeMutation(
        request: MutationRequest,
        ctx: DSLContext
    ): MutationResponse {
        val results = mutationTranslator.translate(
            request,
            ctx,
            sqlGenerator::mutationQueryRequestToSQL
        )
        return MutationResponse(results)
    }

    @WithSpan
    open fun mkDSLCtx(requestArguments: Map<String, Any>?): DSLContext {
        val base = ConnectorConfiguration.Loader.config
        val override = (requestArguments?.get("connection_string") as? String)
        val effectiveConfig = if (override.isNullOrBlank()) {
            base
        } else {
            base.copy(jdbcUrl = io.hasura.ndc.common.JdbcUrlConfig.Literal(override))
        }
        val ds = dataSourceProvider.getDataSource(effectiveConfig)
        return DSL.using(ds, jooqDialect, jooqSettings)
    }

    @WithSpan
    open fun mkDSLCtx(): DSLContext = mkDSLCtx(null)

    @WithSpan
    override fun getSchema(): SchemaResponse {
        return schemaGenerator.getSchema(ConnectorConfiguration.Loader.config)
    }


    @WithSpan
    override fun explainQuery(request: QueryRequest): ExplainResponse {
        val dslCtx = mkDSLCtx(request.request_arguments)
        val query = buildQuery(request)
        val explain = dslCtx.explain(query)
        return ExplainResponse(query = query.sql, lines = listOf(explain.plan()))
    }

    @WithSpan
    override fun handleQuery(request: QueryRequest): List<RowSet> {
        val dslCtx = mkDSLCtx(request.request_arguments)
        val query = buildQuery(request)
        return executeQuery(query, dslCtx)
    }

    @WithSpan
    override fun handleMutation(request: MutationRequest): MutationResponse {
        val dslCtx = mkDSLCtx(request.request_arguments)
        return executeAndSerializeMutation(request, dslCtx)
    }

    @WithSpan
    override fun runHealthCheckQuery(): Boolean {
        val dslCtx = mkDSLCtx()
        val query = dslCtx.selectOne()
        return try {
            dslCtx.fetch(query)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Produces
    fun createDataConnectorService(): IDataConnectorService = this
}


// A no-op implementation of the IDataConnectorService, used when no applicable data connector is found
@Default
@ApplicationScoped
class NoOpDataConnectorService : IDataConnectorService {
    override fun getCapabilities(): CapabilitiesResponse {
        TODO()
    }

    override fun getSchema(): SchemaResponse {
        TODO()
    }

    override fun explainQuery(request: QueryRequest): ExplainResponse {
        TODO()
    }

    override fun handleQuery(request: QueryRequest): List<RowSet> {
        TODO()
    }

    override fun handleMutation(request: MutationRequest): MutationResponse {
        TODO()
    }

    override fun runHealthCheckQuery(): Boolean {
        return false
    }
}