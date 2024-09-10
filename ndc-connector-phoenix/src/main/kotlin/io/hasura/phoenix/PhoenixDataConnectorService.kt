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
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.sqlgen.MutationTranslator
import io.hasura.phoenix.NoRelationshipsQueryGenerator.columnTypeTojOOQType
import jakarta.annotation.Priority
import jakarta.enterprise.inject.Alternative
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jooq.SQLDialect
import org.jooq.conf.RenderOptionalKeyword
import org.jooq.conf.RenderQuotedNames
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType


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
        )
    )

    override fun handleQuery(request: QueryRequest): List<RowSet> {
        val dslCtx = mkDSLCtx()

        val query = NoRelationshipsQueryGenerator.queryRequestToSQL(request)
        println(query)

        if (request.variables != null) {
            val tempTableName = getTempTableName(request)
            dslCtx.execute("""DROP TABLE IF EXISTS "$tempTableName" """)

            val (createTableSQL, upsertValuesSQL) = generateTempTableSQLForQueryVariables(tempTableName, request)
            println(createTableSQL)
            println(upsertValuesSQL)

            dslCtx.execute(createTableSQL)
            upsertValuesSQL.forEach {
                dslCtx.execute(it)
            }
        }

        val rowset = RowSet(
            rows = request.query.fields?.let {
                dslCtx.fetch(query).intoMaps()
            },
            aggregates = request.query.aggregates?.let {
                dslCtx.fetch(query).intoMaps().first()
            }
        )

        return listOf(rowset)
    }

    fun generateTempTableSQLForQueryVariables(name: String, request: QueryRequest): Pair<String, List<String>> {
        val columnMap = buildQueryVariableColumnMapping(request.query.predicate!!)

        val variableTypes = columnMap.entries.associate { (column, variable) ->
            variable to columnTypeTojOOQType(request.collection, ColumnField(column))
        }

        val createTable = DSL.createTable(DSL.name(name))
            .column(DSL.field(DSL.name("idx")), SQLDataType.BIGINT.identity(true))
            .columns(variableTypes.map { (name, type) -> DSL.field(DSL.name(name), type) })
            .constraint(DSL.constraint("pk_temp_vars_${request.collection}").primaryKey("idx"))

        val createTableSQL = DSL.using(SQLDialect.DEFAULT).render(createTable)

        val insertValuesSQL = request.variables!!
            .mapIndexed { idx, row ->
                val individualUpsert = DSL.insertInto(DSL.table(DSL.name(name)))
                    .columns(variableTypes.map { DSL.field(DSL.name(it.key)) } + DSL.field(DSL.name("idx")))
                    .values(variableTypes.map { row[it.key] } + idx)

                DSL
                    .using(SQLDialect.DEFAULT)
                    .renderInlined(individualUpsert)
                    .replace("insert", "upsert")
            }

        return Pair(createTableSQL, insertValuesSQL)
    }

    override val jooqDialect = SQLDialect.POSTGRES
    override val jooqSettings =
        commonDSLContextSettings
            .withRenderQuotedNames(RenderQuotedNames.ALWAYS)
            .withRenderOptionalAsKeywordForFieldAliases(RenderOptionalKeyword.ON)

    override val sqlGenerator = NoRelationshipsQueryGenerator
    override val mutationTranslator = MutationTranslator

    companion object {
        fun getTempTableName(request: QueryRequest): String {
            return "temp_vars_${request.collection}_${request.variables.hashCode()}"
        }

        fun buildQueryVariableColumnMapping(predicate: Expression): Map<String, String> {
            return when (predicate) {
                is Expression.And -> {
                    predicate.expressions.fold(emptyMap()) { acc, expr ->
                        acc + buildQueryVariableColumnMapping(expr)
                    }
                }

                is Expression.Or -> {
                    predicate.expressions.fold(emptyMap()) { acc, expr ->
                        acc + buildQueryVariableColumnMapping(expr)
                    }
                }

                is Expression.Not -> {
                    buildQueryVariableColumnMapping(predicate.expression)
                }

                is Expression.ApplyBinaryComparison -> {
                    when (predicate.value) {
                        is ComparisonValue.VariableComp -> mapOf(
                            predicate.column.name to (predicate.value as ComparisonValue.VariableComp).name
                        )

                        else -> emptyMap()
                    }
                }

                is Expression.ApplyBinaryArrayComparison -> {
                    TODO()
                }

                is Expression.Exists -> {
                    buildQueryVariableColumnMapping(predicate.predicate)
                }

                else -> emptyMap()
            }
        }

        // In: [{"name": "a", "value": 1}, {"name": "b", "value": 2}]
        // Out: {"a": [1], "b": [2]}
        fun extractQueryVariablesToMap(variables: List<Map<String, Any>>): Map<String, List<Any>> {
            return variables.fold(emptyMap()) { acc, variableMap ->
                variableMap.entries.fold(acc) { acc, (key, value) ->
                    acc + (key to (acc[key] ?: emptyList()) + value)
                }
            }
        }
    }
}
