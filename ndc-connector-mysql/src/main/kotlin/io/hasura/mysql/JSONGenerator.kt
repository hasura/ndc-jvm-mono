package io.hasura.mysql

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.common.NativeQueryInfo
import io.hasura.ndc.common.NativeQueryPart
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.CustomField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType


object JsonQueryGenerator : BaseQueryGenerator() {

    override fun forEachQueryRequestToSQL(request: QueryRequest): Select<*> {
        return DSL.with(buildVarsCTE(request))
            .select()
            .from(queryRequestToSQLInternal(request), DSL.table(DSL.name("vars")))
    }

    override fun queryRequestToSQL(request: QueryRequest): Select<*> {
        return queryRequestToSQLInternal(request)
    }

    fun queryRequestToSQLInternal(
        request: QueryRequest,
    ): SelectSelectStep<*> {
        // If the QueryRequest "collection" references the name of a Native Query defined in the configuration.json,
        // we need to prefix the generated query with a CTE named identically to the Native Query, containing the Native Query itself
        val isNativeQuery = ConnectorConfiguration.Loader.config.nativeQueries.containsKey(request.collection)

        return if (isNativeQuery) {
            mkNativeQueryCTE(request).select(
                jsonArrayAgg(
                    buildJSONSelectionForQueryRequest(request)
                )
            )
        } else {
            DSL.select(
                jsonArrayAgg(
                    buildJSONSelectionForQueryRequest(request)
                )
            )
        }
    }

    fun buildJSONSelectionForQueryRequest(
        request: QueryRequest,
        parentTable: String? = null,
        parentRelationship: Relationship? = null
    ): JSONObjectNullStep<*> {

        val baseSelection = DSL.select(
            DSL.table(DSL.name(request.collection)).asterisk()
        ).from(
            if (request.query.predicate == null) {
                DSL.table(DSL.name(request.collection))
            } else {
                val table = DSL.table(DSL.name(request.collection))
                val requiredJoinTables = collectRequiredJoinTablesForWhereClause(
                    where = request.query.predicate!!,
                    collectionRelationships = request.collection_relationships
                )
                requiredJoinTables.foldIndexed(table) { index, acc, relationship ->
                    val parentTable = if (index == 0) {
                        request.collection
                    } else {
                        requiredJoinTables.elementAt(index - 1).target_collection
                    }

                    val joinTable = DSL.table(DSL.name(relationship.target_collection))
                    acc.join(joinTable).on(
                        mkJoinWhereClause(
                            sourceTable = parentTable,
                            parentRelationship = relationship
                        )
                    )
                }
            }
        ).apply {
            if (request.query.predicate != null) {
                where(getWhereConditions(request))
            }
            if (parentRelationship != null) {
                where(
                    mkJoinWhereClause(
                        sourceTable = parentTable ?: error("parentTable is null"),
                        parentRelationship = parentRelationship
                    )
                )
            }
            if (request.query.order_by != null) {
                orderBy(
                    translateIROrderByField(
                        orderBy = request.query.order_by,
                        currentCollection = getTableName(request.collection),
                        relationships = request.collection_relationships
                    )
                )
            }
            if (request.query.limit != null) {
                limit(request.query.limit)
            }
            if (request.query.offset != null) {
                offset(request.query.offset)
            }
        }.asTable(
            DSL.name(getTableName(request.collection))
        )

        return DSL.jsonObject(
            buildList {
                if (!request.query.fields.isNullOrEmpty()) {
                    add(
                        DSL.jsonEntry(
                            "rows",
                            DSL.select(
                                jsonArrayAgg(
                                    DSL.jsonObject(
                                        (request.query.fields ?: emptyMap()).map { (alias, field) ->
                                            when (field) {
                                                is ColumnField -> {
                                                    DSL.jsonEntry(
                                                        alias,
                                                        DSL.field(
                                                            DSL.name(field.column),
                                                            // columnTypeTojOOQType(request.collection, field)
                                                        )
                                                    )
                                                }

                                                is IRField.RelationshipField -> {
                                                    val relationship =
                                                        request.collection_relationships[field.relationship]
                                                            ?: error("Relationship ${field.relationship} not found")

                                                    val subQuery = buildJSONSelectionForQueryRequest(
                                                        parentTable = request.collection,
                                                        parentRelationship = relationship,
                                                        request = QueryRequest(
                                                            collection = relationship.target_collection,
                                                            collection_relationships = request.collection_relationships,
                                                            query = field.query,
                                                            arguments = field.arguments,
                                                            variables = null
                                                        )
                                                    )

                                                    DSL.jsonEntry(
                                                        alias,
                                                        DSL.coalesce(
                                                            DSL.select(subQuery),
                                                            DSL.jsonObject(
                                                                DSL.jsonEntry(
                                                                    "rows",
                                                                    DSL.jsonArray()
                                                                )
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    )
                                )
                            ).from(
                                baseSelection
                            )
                        )
                    )
                }
                if (!request.query.aggregates.isNullOrEmpty()) {
                    add(
                        DSL.jsonEntry(
                            "aggregates",
                            DSL.select(
                                DSL.jsonObject(
                                    (request.query.aggregates ?: emptyMap()).map { (alias, aggregate) ->
                                        DSL.jsonEntry(
                                            alias,
                                            getAggregatejOOQFunction(aggregate)
                                        )
                                    }
                                )
                            ).from(
                                baseSelection
                            )
                        )
                    )
                }
            }
        )
    }

    fun renderNativeQuerySQL(
        nativeQuery: NativeQueryInfo,
        arguments: Map<String, Argument>
    ): String {
        val sql = nativeQuery.sql
        val parts = sql.parts

        return parts.joinToString("") { part ->
            when (part) {
                is NativeQueryPart.Text -> part.value
                is NativeQueryPart.Parameter -> {
                    val argument = arguments[part.value] ?: error("Argument ${part.value} not found")
                    when (argument) {
                        is Argument.Literal -> argument.value.toString()
                        else -> error("Only literals are supported in Native Queries in this version")
                    }
                }
            }
        }
    }

    fun jsonArrayAgg(field: JSONObjectNullStep<*>) = CustomField.of("mysql_json_arrayagg", SQLDataType.JSON) {
        it.visit(DSL.field("json_arrayagg({0})", field))
    }

    fun collectRequiredJoinTablesForWhereClause(
        where: Expression,
        collectionRelationships: Map<String, Relationship>,
        previousTableName: String? = null
    ): Set<Relationship> {
        return when (where) {
            is ExpressionOnColumn -> when (val column = where.column) {
                is ComparisonColumn.Column -> {
                    column.path.fold(emptySet()) { acc, path ->
                        val relationship = collectionRelationships[path.relationship]
                            ?: error("Relationship ${path.relationship} not found")

                        acc + relationship
                    }
                }

                else -> emptySet()
            }

            is Expression.And -> where.expressions.fold(emptySet()) { acc, expr ->
                acc + collectRequiredJoinTablesForWhereClause(expr, collectionRelationships)
            }

            is Expression.Or -> where.expressions.fold(emptySet()) { acc, expr ->
                acc + collectRequiredJoinTablesForWhereClause(expr, collectionRelationships)
            }

            is Expression.Not -> collectRequiredJoinTablesForWhereClause(where.expression, collectionRelationships)

            else -> emptySet()
        }
    }

    fun ndcScalarTypeToSQLDataType(scalarType: NDCScalar): DataType<out Any> = when (scalarType) {
        NDCScalar.BOOLEAN -> SQLDataType.BOOLEAN
        NDCScalar.INT -> SQLDataType.INTEGER
        NDCScalar.FLOAT -> SQLDataType.FLOAT
        NDCScalar.STRING -> SQLDataType.CLOB
        NDCScalar.DATE -> SQLDataType.DATE
        NDCScalar.DATETIME -> SQLDataType.TIMESTAMP
        NDCScalar.DATETIME_WITH_TIMEZONE -> SQLDataType.TIMESTAMP
        NDCScalar.TIME -> SQLDataType.TIME
        NDCScalar.TIME_WITH_TIMEZONE -> SQLDataType.TIME
    }

    private fun columnTypeTojOOQType(collection: String, field: ColumnField): org.jooq.DataType<out Any> {
        val connectorConfig = ConnectorConfiguration.Loader.config

        val table = connectorConfig.tables.find { it.tableName == collection }
            ?: error("Table $collection not found in connector configuration")

        val column = table.columns.find { it.name == field.column }
            ?: error("Column ${field.column} not found in table $collection")

        val scalarType = MySQLJDBCSchemaGenerator.mapScalarType(column.type, column.numeric_scale)
        return ndcScalarTypeToSQLDataType(scalarType)
    }

    private fun getAggregatejOOQFunction(aggregate: Aggregate) = when (aggregate) {
        is Aggregate.StarCount -> DSL.count()
        is Aggregate.SingleColumn -> {
            val col = DSL.field(DSL.name(aggregate.column)) as Field<Number>
            when (aggregate.function) {
                SingleColumnAggregateFunction.AVG -> DSL.avg(col)
                SingleColumnAggregateFunction.MAX -> DSL.max(col)
                SingleColumnAggregateFunction.MIN -> DSL.min(col)
                SingleColumnAggregateFunction.SUM -> DSL.sum(col)
                SingleColumnAggregateFunction.STDDEV_POP -> DSL.stddevPop(col)
                SingleColumnAggregateFunction.STDDEV_SAMP -> DSL.stddevSamp(col)
                SingleColumnAggregateFunction.VAR_POP -> DSL.varPop(col)
                SingleColumnAggregateFunction.VAR_SAMP -> DSL.varSamp(col)
            }
        }

        is Aggregate.ColumnCount -> {
            val col = DSL.field(DSL.name(aggregate.column))
            if (aggregate.distinct) DSL.countDistinct(col) else DSL.count(col)
        }
    }

    private fun mkJoinWhereClause(
        sourceTable: String,
        parentRelationship: Relationship
    ) = DSL.and(
        parentRelationship.column_mapping.map { (from, to) ->
            val childField = DSL.field(DSL.name(getTableName(sourceTable), from))
            val parentField = DSL.field(DSL.name(parentRelationship.target_collection, to))
            childField.eq(parentField)
        }
    )

    private fun getTableName(collection: String): String {
        return collection.split('.').last()
    }

}
