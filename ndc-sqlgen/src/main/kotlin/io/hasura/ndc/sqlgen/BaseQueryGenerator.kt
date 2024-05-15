package io.hasura.ndc.sqlgen

import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.extensions.isVariablesRequest
import io.hasura.ndc.ir.Field as IRField
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

abstract class BaseQueryGenerator : BaseGenerator {

    fun handleRequest(request: QueryRequest): Select<*> {
        return if(request.isVariablesRequest())
            this.forEachQueryRequestToSQL(request)
        else this.queryRequestToSQL(request)
    }

    abstract fun queryRequestToSQL(request: QueryRequest): Select<*>

    open fun mutationQueryRequestToSQL(request: QueryRequest): Select<*> {
        throw NotImplementedError("Mutation not supported for this data source")
    }

    fun getQueryColumnFields(fields: Map<String, IRField>): Map<String, IRField.ColumnField> {
        return fields
            .filterValues { it is IRField.ColumnField }
            .mapValues { it.value as IRField.ColumnField }
    }

    fun getQueryRelationFields(fields: Map<String, IRField>?): Map<String, IRField.RelationshipField> {
        return fields
            ?.filterValues { it is IRField.RelationshipField }
            ?.mapValues { it.value as IRField.RelationshipField }
            ?: emptyMap()
    }

    protected fun getAggregateFields(request: QueryRequest): Map<String, Aggregate> {
        return (request.query.aggregates ?: emptyMap())
    }

    protected fun mkJoinKeyFields(
        rel: Relationship?,
        currentCollection: String,
    ): List<Field<Any>> {
        return if (rel == null) emptyList()
        else if (rel.column_mapping.isNotEmpty())
            rel.column_mapping.values.map { it }.map { DSL.field(DSL.name(listOf(currentCollection, it))) }
        else if (rel.arguments.isNotEmpty())
            rel.arguments.keys.map { DSL.field(DSL.name(it)) }
        else emptyList()
    }

    fun mkAggregateSubquery(
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

        val joinCols = mkJoinKeyFields(relationship, relationship.target_collection)

        // Select fields that need to be present in order for the ORDER BY clause to work
        return DSL.select(
            orderElem + joinCols
        ).from(
            DSL.table(DSL.name(relationship.target_collection))
        ).where(
            whereCondition
        ).groupBy(
            joinCols
        )
    }

    protected fun translateIROrderByField(
        request: QueryRequest,
        currentCollection: String = request.collection
    ): List<SortField<*>> {
        return translateIROrderByField(request.query.order_by, currentCollection, request.collection_relationships)
    }

    // Translates the IR "order_by" field into a list of JOOQ SortField objects
    // This method requires that order-by target fields which reference other tables
    // have been JOIN'ed to the main query table, aliased as their Relationship table name
    // TODO: Does this break if custom table names are used?
    protected fun translateIROrderByField(
        orderBy: OrderBy?,
        currentCollection: String,
        relationships: Map<String, Relationship>
    ): List<SortField<*>> {
        return orderBy?.elements?.map { elem ->
            val field = when (val target = elem.target) {
                is OrderByTarget.OrderByColumn -> {
                    if (elem.target.path.isNotEmpty()) {
                        val relName = elem.target.path.map { it.relationship }.last()
                        val rel = relationships[relName] ?: throw Exception("Relationship not found")
                        val targetTable = rel.target_collection
                        DSL.field(DSL.name(listOf(targetTable, target.name)))
                    } else {
                        DSL.field(DSL.name(listOf(currentCollection, target.name)))
                    }
                }


                is OrderByTarget.OrderByStarCountAggregate,
                is OrderByTarget.OrderBySingleColumnAggregate -> {
                    DSL.coalesce(
                        if (elem.target.path.isNotEmpty()) {
                            val targetCollection = elem.target.path.last().relationship
                            DSL.field(DSL.name(targetCollection + "_aggregate", "aggregate_field"))
                        } else {
                            DSL.field(DSL.name("aggregate_field"))
                        },
                        DSL.zero() as SelectField<*>
                    )
                }
            }

            when (elem.order_direction) {
                OrderDirection.Asc -> field.asc().nullsLast()
                OrderDirection.Desc -> field.desc().nullsFirst()
            }
        } ?: emptyList()
    }

    protected fun addJoinsRequiredForPredicate(
        request: QueryRequest,
        select: SelectJoinStep<*>,
        expression: Expression? = request.query.where,
        seenRelations: MutableSet<String> = mutableSetOf()
    ) {
        fun addForColumn(column: ComparisonColumn) {
            if (column is ComparisonColumn.Column) {
                column.path.forEach {
                    if (!seenRelations.contains(it.relationship)) {
                        val r = request.collection_relationships[it.relationship]!!
                        val rel = r.copy(arguments = it.arguments + r.arguments)
                        select.leftJoin(
                            DSL.table(DSL.name(rel.target_collection))
                        ).on(
                            mkSQLJoin(rel, request.collection )
                        )
                        seenRelations.add(it.relationship)
                    }
                    addJoinsRequiredForPredicate(request, select, it.predicate, seenRelations)
                }
            }
        }

        expression?.let { where ->
            when (where) {
                is Expression.And ->
                    where.expressions.forEach { addJoinsRequiredForPredicate(request, select, it, seenRelations) }

                is Expression.Or ->
                    where.expressions.forEach { addJoinsRequiredForPredicate(request, select, it, seenRelations) }

                is Expression.Not -> addJoinsRequiredForPredicate(request, select, where.expression, seenRelations)
                is Expression.ApplyBinaryComparison -> {
                    addForColumn(where.column)
                    if (where.value is ComparisonValue.ColumnComp) {
                        addForColumn((where.value as ComparisonValue.ColumnComp).column)
                    }
                }

                is Expression.ApplyBinaryArrayComparison -> {
                    addForColumn(where.column)
                    where.values.filterIsInstance<ComparisonValue.ColumnComp>()
                        .forEach { addForColumn(it.column) }
                }

                is Expression.ApplyUnaryComparison -> {} // no-op
                is Expression.Exists -> addJoinsRequiredForPredicate(request, select, where.where, seenRelations)
            }
        }

    }

    protected fun addJoinsRequiredForOrderByFields(
        select: SelectJoinStep<*>,
        request: QueryRequest,
        sourceCollection: String = request.collection
    ) {
        // Add the JOIN's required by any ORDER BY fields referencing other tables
        //
        // Make a SET to hold seen tables so we don't add the same JOIN twice
        // When we are mapping through each of the ORDER BY elements, we will
        // check if the "relEdges" for the target path have been seen before
        // If so, we don't want to add the JOIN again, because it will cause
        // a SQL error
        val seenRelations = mutableSetOf<String>()
        request.query.order_by?.let { orderBy ->
            // FOR EACH ORDER BY:
            orderBy.elements.forEach { orderByElement ->
                if (orderByElement.target.path.isNotEmpty()) {
                    val relationshipNames = orderByElement.target.path.map { it.relationship to it.predicate }.toMap()

                    // FOR EACH RELATIONSHIP EDGE:
                    relationshipNames
                        .minus(seenRelations) // Only add JOINs for unseen relationships
                        .forEach { (relationshipName, predicate) ->
                            seenRelations.add(relationshipName)

                            val relationship = request.collection_relationships[relationshipName]
                                ?: throw Exception("Relationship not found")

                            val orderByWhereCondition = expressionToCondition(
                                e = predicate,
                                request
                            )

                            when (relationship.relationship_type) {
                                RelationshipType.Object -> {
                                    select.leftJoin(
                                        DSL.table(DSL.name(relationship.target_collection))
                                    ).on(
                                        mkSQLJoin(relationship, sourceCollection).and(orderByWhereCondition)
                                    )
                                }
                                // It's an aggregate relationship, so we need to join to the aggregation subquery
                                RelationshipType.Array -> {
                                    select.leftJoin(
                                        mkAggregateSubquery(
                                            elem = orderByElement,
                                            relationship = relationship,
                                            whereCondition = orderByWhereCondition,
                                        ).asTable(
                                            DSL.name(relationshipName + "_aggregate")
                                        )
                                    ).on(
                                        mkSQLJoin(
                                            relationship,
                                            sourceCollection,
                                            targetTableNameTransform = { _ -> "${relationshipName}_aggregate" }
                                        )
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    protected fun translateIRAggregateField(field: Aggregate): AggregateFunction<*> {
        return when (field) {
            is Aggregate.StarCount -> DSL.count()
            is Aggregate.ColumnCount ->
                if (field.distinct)
                    DSL.countDistinct(DSL.field(DSL.name(field.column)))
                else
                    DSL.count(DSL.field(DSL.name(field.column)))

            is Aggregate.SingleColumn -> {
                val jooqField =
                    DSL.field(DSL.name(field.column), SQLDataType.NUMERIC)
                when (field.function) {
                    SingleColumnAggregateFunction.AVG -> DSL.avg(jooqField)
                    SingleColumnAggregateFunction.SUM -> DSL.sum(jooqField)
                    SingleColumnAggregateFunction.MIN -> DSL.min(jooqField)
                    SingleColumnAggregateFunction.MAX -> DSL.max(jooqField)
                    SingleColumnAggregateFunction.STDDEV_POP -> DSL.stddevPop(jooqField)
                    SingleColumnAggregateFunction.STDDEV_SAMP -> DSL.stddevSamp(jooqField)
                    SingleColumnAggregateFunction.VAR_POP -> DSL.varPop(jooqField)
                    SingleColumnAggregateFunction.VAR_SAMP -> DSL.varSamp(jooqField)
                }
            }
        }
    }

    protected fun translateIRAggregateFields(fields: Map<String, Aggregate>): List<Field<*>> {
        return fields.map { (alias, field) ->
            translateIRAggregateField(field).`as`(alias)
        }
    }

    protected fun isAggregateOnlyRequest(request: QueryRequest) =
        getQueryColumnFields(request.query.fields ?: emptyMap()).isEmpty() &&
                getAggregateFields(request).isNotEmpty()

    protected fun buildOuterStructure(
        request: QueryRequest,
        buildRows: (request: QueryRequest) -> Field<*>,
        buildAggregates: (request: QueryRequest) -> Field<*> = ::buildAggregates
    ): Field<*> {
        return DSL.jsonObject(
            if (isAggregateOnlyRequest(request)) {
                DSL.jsonEntry(
                    "aggregates",
                    getAggregates(request, buildAggregates)
                )
            } else {
                DSL.jsonEntry(
                    "rows",
                    getRows(request, buildRows)
                )
            }
        )
    }

    private fun getRows(
        request: QueryRequest,
        buildRows: (request: QueryRequest) -> Field<*>
    ): Field<*> {
        return buildRows(request)
    }

    private fun getAggregates(
        request: QueryRequest,
        buildAggregates: (request: QueryRequest) -> Field<*>
    ): Field<*> {
        return getAggregateFields(request).let {
            buildAggregates(request)
        }
    }

    protected open fun buildAggregates(request: QueryRequest): Field<*> {
        return DSL.jsonObject(
            getAggregateFields(request).map { (alias, aggregate) ->
                DSL.jsonEntry(
                    alias,
                    translateIRAggregateField(aggregate)
                )
            }
        )
    }

    protected fun mkOffsetLimit(
        request: QueryRequest,
        rowNumber: Field<Any> = DSL.field(DSL.name("rn"))
    ): Condition {
        val limit = (request.query.limit ?: 0)
        val offset = (request.query.offset ?: 0)
        return when {
            limit > 0 && offset > 0 -> {
                (rowNumber.le(DSL.inline(limit + offset)))
                    .and(rowNumber.gt(DSL.inline(offset)))
            }

            limit > 0 -> {
                (rowNumber.le(DSL.inline(limit)))
            }

            offset > 0 -> {
                (rowNumber.gt(DSL.inline(offset)))
            }

            else -> {
                DSL.noCondition()
            }
        }
    }

    protected fun buildVarsCTE(request: QueryRequest, suffix: String = ""): CommonTableExpression<*> {
        if (request.variables.isNullOrEmpty()) throw Exception("No variables found")

        val fields = request.variables!!.flatMap { it.keys }.toSet()
        return DSL
            .name(VARS + suffix)
            .fields(*fields.toTypedArray().plus(INDEX))
            .`as`(
                request.variables!!.mapIndexed { idx, variable ->
                    val f = variable.values.map { value ->
                        DSL.inline(value)
                    }
                    DSL.select(
                        *f.toTypedArray(),
                        DSL.inline(idx)
                    )
                }.reduce { acc: SelectOrderByStep<Record>, select: SelectSelectStep<Record> ->
                    acc.unionAll(select)
                }
            )

    }

    //
    abstract fun forEachQueryRequestToSQL(request: QueryRequest): Select<*>

    protected fun getWhereConditions(
        request: QueryRequest,
        collection: String = request.collection,
        arguments: Map<String, Argument> = request.arguments
    ): Condition {
        return DSL.and(
            if (collection == request.root_collection) {
                arguments.map { argumentToCondition(request, it) }
            } else { listOf(DSL.noCondition()) } +
            listOf(request.query.where?.let { where ->
                expressionToCondition(
                    e = where,
                    request
                )
            } ?: DSL.noCondition()))
    }

    protected fun getDefaultAggregateJsonEntries(aggregates: Map<String, Aggregate>?): Field<*> {
        val defaults = aggregates?.map { (alias, agg) ->
            DSL.jsonEntry(
                DSL.inline(alias),
                when (agg) {
                    is Aggregate.SingleColumn -> DSL.nullCondition()
                    is Aggregate.StarCount -> DSL.zero()
                    is Aggregate.ColumnCount -> DSL.zero()
                }
            )
        }
        return if (defaults.isNullOrEmpty()) DSL.inline(null as JSON?) else DSL.jsonObject(defaults)
    }


    companion object {
        const val MAX_QUERY_ROWS = 2147483647
        const val FOREACH_ROWS = "foreach_rows"
        const val VARS = "vars"
        const val INDEX = "index"
        const val ROWS_AND_AGGREGATES = "rows_and_aggregates"
    }
}
