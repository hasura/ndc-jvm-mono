package io.hasura.mysql

import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import io.hasura.ndc.sqlgen.DatabaseType
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.DSL


fun tojOOQName(name: String): Name {
    return DSL.name(name.split("."))
}

fun tojOOQName(name: String, vararg parts: String): Name {
    return DSL.name(name.split(".") + parts.toList())
}

object JsonQueryGenerator : BaseQueryGenerator() {

    override fun forEachQueryRequestToSQL(request: QueryRequest): Select<*> {
        return DSL
            .with(buildVarsCTE(request))
            .select(
                DSL.jsonArrayAgg(
                    buildJSONSelectionForQueryRequest(request)
                )
            )
            .from(
                DSL.table(DSL.name("vars"))
            )
    }

    override fun queryRequestToSQL(request: QueryRequest): Select<*> {
        return queryRequestToSQLInternal(request)
    }

    private fun queryRequestToSQLInternal(
        request: QueryRequest,
    ): SelectSelectStep<*> {
        // JOOQ is smart enough to not generate CTEs if there are no native queries
        return mkNativeQueryCTEs(request).select(
            DSL.jsonArrayAgg(
                buildJSONSelectionForQueryRequest(request)
            )
        )
    }

    fun buildJSONSelectionForQueryRequest(
        request: QueryRequest,
        parentTable: String? = null,
        parentRelationship: Relationship? = null
    ): JSONObjectNullStep<*> {
        val baseTable = DSL.table(tojOOQName(request.collection))

        val baseQuery = DSL.select(
            baseTable.asterisk()
        ).select(
            getSelectOrderFields(request)
        ).from(
            baseTable
        )

        if (request.query.predicate != null) {
            val requiredJoinTables = collectRequiredJoinTablesForWhereClause(
                where = request.query.predicate!!,
                collectionRelationships = request.collection_relationships
            )
            requiredJoinTables.forEach { relationship ->
                baseQuery.leftJoin(DSL.table(tojOOQName(relationship.target_collection)))
                    .on(mkJoinWhereClause(request.collection, relationship))
            }
        }

        addJoinsRequiredForOrderByFields2(baseQuery, request)

        if (parentRelationship != null && parentTable != null) {
            baseQuery.where(
                mkJoinWhereClause(parentTable, parentRelationship)
            )
        }

        if (request.query.predicate != null) {
            baseQuery.where(getWhereConditions(request))
        }
        if (request.query.order_by != null) {
            baseQuery.orderBy(
                translateIROrderByField(
                    orderBy = request.query.order_by,
                    currentCollection = request.collection,
                    relationships = request.collection_relationships
                )
            )
        }
        if (request.query.limit != null) {
            baseQuery.limit(request.query.limit)
        }
        if (request.query.offset != null) {
            baseQuery.offset(request.query.offset)
        }

        val baseSelection = baseQuery.asTable(DSL.name(tojOOQName(request.collection)))

        return DSL.jsonObject(
            buildList {
                if (!request.query.fields.isNullOrEmpty()) {
                    add(
                        DSL.jsonEntry(
                            "rows",
                            DSL.select(
                                DSL.nvl(
                                    DSL.jsonArrayAgg(
                                        DSL.jsonObject(
                                            (request.query.fields ?: emptyMap()).map { (alias, field) ->
                                                when (field) {
                                                    is ColumnField -> {
                                                        val columnField = DSL.field(DSL.name(field.column))
                                                        val (columnType, ndcScalar) = columnTypeTojOOQType(
                                                            MySQLJDBCSchemaGenerator::mapScalarType,
                                                            request.collection,
                                                            field
                                                        )
                                                        val castedField = castToSQLDataType(
                                                            DatabaseType.MYSQL,
                                                            columnField,
                                                            ndcScalar
                                                        )
                                                        DSL.jsonEntry(
                                                            alias,
                                                            castedField
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
                                    ).orderBy(
                                        getConcatOrderFields(request)
                                    ),
                                    DSL.jsonArray()
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
                                DSL.nvl(
                                    DSL.jsonObject(
                                        (request.query.aggregates ?: emptyMap()).map { (alias, aggregate) ->
                                            DSL.jsonEntry(
                                                alias,
                                                getAggregatejOOQFunction(aggregate)
                                            )
                                        }
                                    ),
                                    DSL.jsonObject()
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

    private fun collectRequiredJoinTablesForWhereClause(
        where: Expression,
        collectionRelationships: Map<String, Relationship>,
        previousTableName: String? = null
    ): Set<Relationship> {
        return when (where) {
            is ExpressionOnColumn -> when (val column = where.column) {
                is ComparisonTarget.Column -> {
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

    private fun getAggregatejOOQFunction(aggregate: Aggregate) = when (aggregate) {
        is Aggregate.StarCount -> DSL.count()
        is Aggregate.SingleColumn -> {
            val col = DSL.field(DSL.name(aggregate.column)) as Field<Number>
            when (aggregate.function) {
                SingleColumnAggregateFunction.AVG -> DSL.avg(col)
                SingleColumnAggregateFunction.MAX -> DSL.max(col)
                SingleColumnAggregateFunction.MIN -> DSL.min(col)
                SingleColumnAggregateFunction.COUNT -> DSL.count(col)
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
            val childField = DSL.field(tojOOQName(sourceTable, from))
            val parentField = DSL.field(tojOOQName(parentRelationship.target_collection, to))
            childField.eq(parentField)
        }
    )



    private const val ORDER_FIELD_SUFFIX = "_order_field"

    private fun getSelectOrderFields(request: QueryRequest): List<Field<*>> {
        val sortFields = translateIROrderByField(request, request.collection)
        return sortFields.map { it.`$field`().`as`(it.name + ORDER_FIELD_SUFFIX) }
    }

    private fun getConcatOrderFields(request: QueryRequest): List<SortField<*>> {
        val sortFields = translateIROrderByField(request, request.collection)
        return sortFields.map {
            val field = DSL.field(DSL.name(it.name + ORDER_FIELD_SUFFIX))
            when (it.order) {
                SortOrder.ASC -> field.asc().nullsLast()
                SortOrder.DESC -> field.desc().nullsFirst()
                else -> field.asc().nullsLast()
            }
        }
    }

    fun addJoinsRequiredForOrderByFields2(
        select: SelectJoinStep<*>,
        request: QueryRequest,
        sourceCollection: String = request.collection
    ) {
        val seenRelations = mutableSetOf<List<String>>()
        request.query.order_by?.elements?.forEach { orderByElement ->
            if (orderByElement.target.path.isNotEmpty()) {
                var currentCollection = sourceCollection
                orderByElement.target.path.forEach { pathElem ->
                    val relationshipName = pathElem.relationship
                    val relChain = listOf(currentCollection, relationshipName)

                    if (!seenRelations.contains(relChain)) {
                        seenRelations.add(relChain)

                        val relationship = request.collection_relationships[relationshipName]
                            ?: throw Exception("Relationship not found")

                        when (relationship.relationship_type) {
                            RelationshipType.Object -> {
                                select.leftJoin(
                                    DSL.table(tojOOQName(relationship.target_collection))
                                ).on(
                                    mkSQLJoin2(
                                        rel = relationship,
                                        sourceCollection = currentCollection,
                                    )
                                )
                            }
                            RelationshipType.Array -> {
                                select.leftJoin(
                                    mkAggregateSubquery2(
                                        elem = orderByElement,
                                        relationship = relationship,
                                        whereCondition = DSL.trueCondition()
                                    ).asTable(
                                        DSL.name(relationshipName + "_aggregate")
                                    )
                                ).on(
                                    mkSQLJoin2(
                                        rel = relationship,
                                        sourceCollection = currentCollection,
                                        targetTableNameTransform = { "${relationshipName}_aggregate" }
                                    )
                                )
                            }
                        }
                    }

                    val relationship = request.collection_relationships[relationshipName]
                        ?: throw Exception("Relationship not found")

                    currentCollection = relationship.target_collection
                }
            }
        }
    }

    fun mkSQLJoin2(
        rel: Relationship,
        sourceCollection: String,
        targetTableNameTransform: (String) -> String = { it },
    ): Condition {
        val targetTableFQN = targetTableNameTransform(rel.target_collection)
        return DSL.and(
            rel.column_mapping.map { (sourceColumn, targetColumn) ->
                DSL.field(tojOOQName(sourceCollection, sourceColumn))
                    .eq(DSL.field(DSL.name(targetTableFQN.split(".") + targetColumn)))
            }
                    + rel.arguments.map { (targetColumn, argument) ->
                DSL.field(tojOOQName(sourceCollection, (argument as Argument.Column).name))
                    .eq(DSL.field(DSL.name(targetTableFQN.split(".") + targetColumn)))
            }
        )
    }


    fun mkAggregateSubquery2(
        elem: OrderByElement,
        relationship: Relationship,
        whereCondition: Condition
    ): SelectHavingStep<Record> {
        // If the target is a star-count aggregate, we need to select the special aggregate_count field
        // Otherwise, it's a regular aggregate, so our "SELECT *" will give us access to it in the other
        // parts of the query
        val orderElem = when (val target = elem.target) {
            is OrderByTarget.OrderByStarCountAggregate -> {
                listOf(DSL.count().`as`(DSL.name("aggregate_field")))
            }

            is OrderByTarget.OrderBySingleColumnAggregate -> {
                val aggregate = Aggregate.SingleColumn(target.column, target.function)
                listOf(translateIRAggregateField(aggregate).`as`(DSL.name("aggregate_field")))
            }

            else -> {
                emptyList()
            }
        }

        fun mkJoinKeyFields2(
            rel: Relationship?,
            currentCollection: String,
        ): List<Field<Any>> {
            return if (rel == null)
                emptyList()
            else if (rel.column_mapping.isNotEmpty())
                rel.column_mapping.values.map { DSL.field(tojOOQName(currentCollection, it)) }
            else if (rel.arguments.isNotEmpty())
                rel.arguments.keys.map { DSL.field(DSL.name(it)) }
            else emptyList()
        }

        val joinCols = mkJoinKeyFields2(relationship, relationship.target_collection)

        // Select fields that need to be present in order for the ORDER BY clause to work
        return DSL.select(
            orderElem + joinCols
        ).from(
            DSL.table(tojOOQName(relationship.target_collection))
        ).where(
            whereCondition
        ).groupBy(
            joinCols
        )
    }

}
