package io.hasura.ndc.sqlgen

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.NativeQueryInfo
import io.hasura.ndc.common.NativeQueryPart
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.common.NativeQuerySql
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.extensions.isVariablesRequest
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Type
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

enum class DatabaseType {
    ORACLE,
    MYSQL,
    SNOWFLAKE,
    TRINO
}

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

    protected fun findAllNativeQueries(request: QueryRequest): Set<String> {
        val nativeQueries = mutableSetOf<String>()
        val config = ConnectorConfiguration.Loader.config

        // Helper function to check if a collection is a native query
        fun checkAndAddNativeQuery(collection: String) {
            if (config.nativeQueries.containsKey(collection)) {
                nativeQueries.add(collection)
            }
        }

        // Check main collection
        checkAndAddNativeQuery(request.collection)

        // Check relationships
        request.collection_relationships.values.forEach { rel ->
            checkAndAddNativeQuery(rel.target_collection)
        }

        // Recursive function to check predicates
        fun checkPredicates(expression: Expression?) {
            when (expression) {
                is Expression.Exists -> {
                    when (val collection = expression.in_collection) {
                        is ExistsInCollection.Related -> {
                            // Check related collection from relationship
                            val rel = request.collection_relationships[collection.relationship]
                                ?: error("Relationship ${collection.relationship} not found")
                            checkAndAddNativeQuery(rel.target_collection)
                        }
                        is ExistsInCollection.Unrelated -> {
                            checkAndAddNativeQuery(collection.collection)
                        }
                    }
                    // Recursively check the predicate within exists
                    checkPredicates(expression.predicate)
                }
                is Expression.And -> expression.expressions.forEach { checkPredicates(it) }
                is Expression.Or -> expression.expressions.forEach { checkPredicates(it) }
                is Expression.Not -> checkPredicates(expression.expression)
                else -> {} // Other expression types don't reference collections
            }
        }

        // Check predicates in the main query
        checkPredicates(request.query.predicate)

        // Check predicates in relationship fields
        request.query.fields?.values?.forEach { field ->
            if (field is IRField.RelationshipField) {
                checkPredicates(field.query.predicate)
            }
        }

        return nativeQueries
    }

    fun mkNativeQueryCTEs(
        request: QueryRequest
    ): org.jooq.WithStep {
        val config = ConnectorConfiguration.Loader.config
        var nativeQueries = findAllNativeQueries(request)

        if (nativeQueries.isEmpty()) {
            // JOOQ is smart enough to not generate CTEs if there are no native queries
            return DSL.with()
        }

        fun renderNativeQuerySQL(
            nativeQuery: NativeQueryInfo,
            arguments: Map<String, Argument>
        ): String {

            val parts = when (val sql = nativeQuery.sql) {
                is NativeQuerySql.Inline -> sql.getParts(ConnectorConfiguration.Loader.CONFIG_DIRECTORY)
                is NativeQuerySql.FromFile -> sql.getParts(ConnectorConfiguration.Loader.CONFIG_DIRECTORY)
            }

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

        val withStep = DSL.with()
        nativeQueries.forEach { collectionName ->
            withStep.with(DSL.name(collectionName))
                .`as`(DSL.resultQuery(
                    renderNativeQuerySQL(
                        config.nativeQueries[collectionName]!!,
                        request.arguments
                    )
                ))
        }

        return withStep
    }

    fun getQueryColumnFields(fields: Map<String, IRField>): Map<String, IRField.ColumnField> {
        return fields
            .filterValues { it is IRField.ColumnField }
            .mapValues { it.value as IRField.ColumnField }
    }

    protected fun getQueryRelationFields(fields: Map<String, IRField>?): Map<String, IRField.RelationshipField> {
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

    protected fun mkJoinKeyFields(
        rel: Relationship?,
        currentCollection: org.jooq.Name,
    ): List<Field<Any>> {
        return if (rel == null) emptyList()
        else if (rel.column_mapping.isNotEmpty())
            rel.column_mapping.values.map { it }.map {
                val appendedName = currentCollection.name.asList<String>() + it
                DSL.field(DSL.name(*appendedName.toTypedArray()))
            }
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
                        DSL.field(DSL.name(targetTable.split(".") + target.name))
                    } else {
                        DSL.field(DSL.name(currentCollection.split(".") + target.name))
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
        expression: Expression? = request.query.predicate,
        seenRelations: MutableSet<String> = mutableSetOf()
    ) {
        fun addForColumn(column: ComparisonTarget) {
            if (column is ComparisonTarget.Column) {
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

                is Expression.ApplyUnaryComparison -> {} // no-op
                is Expression.Exists -> addJoinsRequiredForPredicate(request, select, where.predicate, seenRelations)
            }
        }

    }

    protected fun addJoinsRequiredForOrderByFields(
        select: SelectJoinStep<*>,
        request: QueryRequest,
        sourceCollection: String = request.collection
    ) {
        // Add the JOINs required by any ORDER BY fields referencing other tables
        // Walk each path sequentially, carrying forward the current table alias
        // so that chained relationships join to the previous hop rather than the root.
        val seenRelationChains = mutableSetOf<List<String>>()
        request.query.order_by?.elements?.forEach { orderByElement ->
            if (orderByElement.target.path.isNotEmpty()) {
                var currentTableName = sourceCollection

                orderByElement.target.path.forEach { pathElem ->
                    val relationshipName = pathElem.relationship
                    val relChain = listOf(currentTableName, relationshipName)

                    if (!seenRelationChains.contains(relChain)) {
                        seenRelationChains.add(relChain)

                        val relationship = request.collection_relationships[relationshipName]
                            ?: throw Exception("Relationship not found")

                        val orderByWhereCondition = expressionToCondition(
                            e = pathElem.predicate,
                            request,
                            relationship.target_collection
                        )

                        when (relationship.relationship_type) {
                            RelationshipType.Object -> {
                                select.leftJoin(
                                    DSL.table(DSL.name(relationship.target_collection))
                                ).on(
                                    mkSQLJoin(
                                        relationship,
                                        currentTableName
                                    ).and(orderByWhereCondition)
                                )
                                // Next hop joins from this target table
                                currentTableName = relationship.target_collection
                            }
                            RelationshipType.Array -> {
                                val aggregateAlias = "${relationshipName}_aggregate"
                                select.leftJoin(
                                    mkAggregateSubquery(
                                        elem = orderByElement,
                                        relationship = relationship,
                                        whereCondition = orderByWhereCondition,
                                    ).asTable(
                                        DSL.name(aggregateAlias)
                                    )
                                ).on(
                                    mkSQLJoin(
                                        relationship,
                                        currentTableName,
                                        targetTableNameTransform = { _ -> aggregateAlias }
                                    )
                                )
                                // Next hop joins from the aggregate alias
                                currentTableName = aggregateAlias
                            }
                        }
                    } else {
                        // Even if the join already exists, advance the current table
                        val relationship = request.collection_relationships[relationshipName]
                            ?: throw Exception("Relationship not found")
                        currentTableName = when (relationship.relationship_type) {
                            RelationshipType.Object -> relationship.target_collection
                            RelationshipType.Array -> "${relationshipName}_aggregate"
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
                    SingleColumnAggregateFunction.COUNT -> DSL.count(jooqField)
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
        val hasFields = !(request.query.fields.isNullOrEmpty())
        val hasAggregates = getAggregateFields(request).isNotEmpty()

        val entries = buildList {
            if (hasFields) {
                add(
                    DSL.jsonEntry(
                        "rows",
                        getRows(request, buildRows)
                    )
                )
            }
            if (hasAggregates) {
                add(
                    DSL.jsonEntry(
                        "aggregates",
                        getAggregates(request, buildAggregates)
                    )
                )
            }
        }
        return DSL.jsonObject(entries)
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
            .fields(*fields.plus(INDEX).map { DSL.quotedName(it) }.toTypedArray())
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
            listOf(request.query.predicate?.let { where ->
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
        const val INDEX = "idx"
        const val ROWS_AND_AGGREGATES = "rows_and_aggregates"
    }

    fun castToSQLDataType(databaseType: DatabaseType, field: Field<*>, type: NDCScalar): Field<*> {
        return when (type) {
            NDCScalar.INT64, NDCScalar.BIGINTEGER, NDCScalar.BIGDECIMAL ->
                field.cast(SQLDataType.VARCHAR)
            NDCScalar.GEOMETRY, NDCScalar.GEOGRAPHY ->
                when(databaseType) {
                    DatabaseType.MYSQL -> DSL.cast(field, SQLDataType.JSON)
                    DatabaseType.SNOWFLAKE -> DSL.cast(DSL.field("ST_AsGeoJSON({0})", Any::class.java, field), SQLDataType.JSON)
                    else -> field
                }
            NDCScalar.VECTOR ->
                DSL.cast(DSL.field("TO_ARRAY({0})", Any::class.java, field), SQLDataType.JSON)
            NDCScalar.BOOLEAN -> field.cast(SQLDataType.BOOLEAN)
            else -> field
        }
    }

    fun ndcScalarTypeToSQLDataType(scalarType: NDCScalar): DataType<out Any> = when (scalarType) {
        // Boolean
        NDCScalar.BOOLEAN -> SQLDataType.BOOLEAN

        // Integer Types
        NDCScalar.INT8 -> SQLDataType.TINYINT
        NDCScalar.INT16 -> SQLDataType.SMALLINT
        NDCScalar.INT32 -> SQLDataType.INTEGER
        NDCScalar.INT64 -> SQLDataType.BIGINT

        // Floating-Point Types
        NDCScalar.FLOAT32 -> SQLDataType.FLOAT
        NDCScalar.FLOAT64 -> SQLDataType.DOUBLE

        // Arbitrary Precision Types
        NDCScalar.BIGINTEGER -> SQLDataType.NUMERIC
        NDCScalar.BIGDECIMAL -> SQLDataType.DECIMAL

        // String Types
        NDCScalar.STRING -> SQLDataType.CLOB
        NDCScalar.UUID -> SQLDataType.VARCHAR(36) // UUIDs are typically stored as VARCHAR(36)

        // Date and Time Types
        NDCScalar.DATE -> SQLDataType.DATE
        NDCScalar.TIMESTAMP -> SQLDataType.TIMESTAMP
        NDCScalar.TIMESTAMPTZ -> SQLDataType.TIMESTAMPWITHTIMEZONE

        // GeoJSON Types
        NDCScalar.GEOGRAPHY -> SQLDataType.JSON
        NDCScalar.GEOMETRY -> SQLDataType.JSON

        // Binary Types
        NDCScalar.BYTES -> SQLDataType.BLOB

        // JSON Types
        NDCScalar.JSON -> SQLDataType.JSON

        // Default Fallback
        else -> SQLDataType.CLOB
    }

    fun columnTypeTojOOQType(mapScalarType: (String, Int?, Int?) -> NDCScalar, collection: String, field: ColumnField): Pair<org.jooq.DataType<out Any>, NDCScalar> {
        val connectorConfig = ConnectorConfiguration.Loader.config

        val collectionIsTable = connectorConfig.tables.any { it.tableName == collection }
        val collectionIsNativeQuery = connectorConfig.nativeQueries.containsKey(collection)

        if (!collectionIsTable && !collectionIsNativeQuery) {
            error("Collection $collection not found in connector configuration")
        }

        val scalarType =  when {
            collectionIsTable -> {
                val table = connectorConfig.tables.find { it.tableName == collection }
                    ?: error("Table $collection not found in connector configuration")

                val column = table.columns.find { it.name == field.column }
                    ?: error("Column ${field.column} not found in table $collection")

                mapScalarType(column.type, column.numeric_precision, column.numeric_scale)
            }

            collectionIsNativeQuery -> {
                val nativeQuery = connectorConfig.nativeQueries[collection]
                    ?: error("Native query $collection not found in connector configuration")

                val column = nativeQuery.columns[field.column]
                    ?: error("Column ${field.column} not found in native query $collection")

                mapScalarType(Type.extractBaseType(column), null, null)
            }

            else -> error("Collection $collection not found in connector configuration")
        }

        return Pair(ndcScalarTypeToSQLDataType(scalarType), scalarType)
    }
}
