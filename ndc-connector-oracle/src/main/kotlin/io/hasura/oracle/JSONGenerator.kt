package io.hasura.oracle

import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import io.hasura.ndc.sqlgen.DatabaseType
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType


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
                ).returning(
                    SQLDataType.CLOB
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
            ).returning(
                SQLDataType.CLOB
            )
        )
    }

    fun buildJSONSelectionForQueryRequest(
        request: QueryRequest,
        parentTable: String? = null,
        parentRelationship: Relationship? = null
    ): Field<*> {
        val baseTable = DSL.table(tojOOQName(request.collection))

        val baseQuery = DSL.select(
            baseTable.asterisk()
        ).select(
            getSelectOrderFields(request)
        ).from(
            baseTable
        )

        addJoinsRequiredForOrderByFields2(baseQuery, request)

        if (parentRelationship != null && parentTable != null) {
            baseQuery.where(
                mkJoinWhereClause(parentTable, parentRelationship)
            )
        }

        if (request.query.predicate != null) {
            baseQuery.where(getWhereConditions(request))

            val requiredJoinTables = collectRequiredJoinTablesForWhereClause(
                rootTable = request.collection,
                where = request.query.predicate!!,
                collectionRelationships = request.collection_relationships
            )

            requiredJoinTables.forEach { relationshipInfo ->
                val relationship = request.collection_relationships[relationshipInfo.relationshipName]
                    ?: error("Relationship ${relationshipInfo.relationshipName} not found")

                baseQuery.leftJoin(
                    DSL.table(tojOOQName(relationship.target_collection))
                ).on(
                    mkSQLJoin2(
                        rel = relationship,
                        sourceCollection = relationshipInfo.fromTable,
                        targetTableNameTransform = { relationshipInfo.toTable }
                    )
                )
            }

            // If requiredJoinTables is non-empty, we must add the primary key of the parent table to GROUP BY
            // to prevent duplicate rows
            if (requiredJoinTables.isNotEmpty()) {
                baseQuery.groupBy(
                    getSelectOrderFields(request)
                )
            }

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

        val baseSelection = baseQuery.asTable(
            DSL.name(
                getAliasedTableName(request.collection)
            )
        )

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
                                                            OracleJDBCSchemaGenerator::mapScalarType,
                                                            request.collection,
                                                            field
                                                        )
                                                        val castedField = castToSQLDataType(
                                                            DatabaseType.ORACLE,
                                                            columnField,
                                                            ndcScalar
                                                        )
                                                        DSL.jsonEntry(
                                                            alias,
                                                            // Oracle JSON functions convert DATE to ISO8601 format, which includes a timestamp
                                                            // We need to convert it to a date-only format to preserve the actual date value
                                                            //
                                                            // SEE: https://docs.oracle.com/en/database/oracle/oracle-database/23/adjsn/overview-json-generation.html
                                                            //      (Section: "Result Returned by SQL/JSON Generation Functions")
                                                            when (castedField.dataType) {
                                                                SQLDataType.DATE -> DSL.toChar(castedField, DSL.inline("YYYY-MM-DD HH24:MI:SS"))
                                                                SQLDataType.TIMESTAMP -> DSL.toChar(castedField, DSL.inline("YYYY-MM-DD HH24:MI:SS.FF6"))
                                                                SQLDataType.TIMESTAMPWITHTIMEZONE -> DSL.toChar(castedField, DSL.inline("YYYY-MM-DD HH24:MI:SS.FF6 TZR"))
                                                                else -> castedField
                                                            }
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
                                                                        DSL.jsonArray().returning(
                                                                            SQLDataType.CLOB
                                                                        )
                                                                    )
                                                                ).returning(
                                                                    SQLDataType.CLOB
                                                                )
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    ).orderBy(
                                        getConcatOrderFields(request)
                                    ).returning(
                                        SQLDataType.CLOB
                                    ),
                                    DSL.jsonArray().returning(SQLDataType.CLOB)
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
                                    ).returning(
                                        SQLDataType.CLOB
                                    ),
                                    DSL.jsonObject().returning(SQLDataType.CLOB)
                                )
                            ).from(
                                baseSelection
                            )
                        )
                    )
                }
            }
        ).returning(
            SQLDataType.CLOB
        )
    }

    data class RelationshipInfo(
        val fromTable: String,
        val toTable: String,
        val relationshipName: String,
    )

    // Collect the required relationships from "column.path" and "value.path"
    private fun collectRequiredJoinTablesForWhereClause(
        rootTable: String,
        where: Expression,
        collectionRelationships: Map<String, Relationship>
    ): Set<RelationshipInfo> {
        val requiredRelationships = mutableSetOf<RelationshipInfo>()
        var currentTable = rootTable

        fun collectFromExpression(expression: Expression) {
            fun collectFromPath(path: List<PathElement>) {
                path.forEach { pathElement ->
                    val relationshipName = pathElement.relationship
                    val relationship = collectionRelationships[relationshipName]
                        ?: error("Relationship $relationshipName not found")

                    if (currentTable != relationship.target_collection) {
                        requiredRelationships.add(
                            RelationshipInfo(
                                fromTable = currentTable,
                                toTable = relationship.target_collection,
                                relationshipName = relationshipName
                            )
                        )
                    }
                    currentTable = relationship.target_collection

                    // Recursively collect from the predicate of the path element
                    collectFromExpression(pathElement.predicate)
                }
            }

            when (expression) {
                is Expression.ApplyBinaryComparison -> {
                    if (expression.column is ComparisonTarget.Column) {
                        val columnPath = (expression.column as ComparisonTarget.Column).path
                        collectFromPath(columnPath)
                    }
                    if (expression.value is ComparisonValue.ColumnComp) {
                        val value = (expression.value as ComparisonValue.ColumnComp)
                        if (value.column is ComparisonTarget.Column) {
                            val columnPath = (value.column as ComparisonTarget.Column).path
                            collectFromPath(columnPath)
                        }
                    }
                }

                is Expression.And -> expression.expressions.forEach { collectFromExpression(it) }
                is Expression.Or -> expression.expressions.forEach { collectFromExpression(it) }
                is Expression.Not -> collectFromExpression(expression.expression)
                else -> {}
            }
        }

        collectFromExpression(where)
        return requiredRelationships.distinctBy { it.relationshipName }.toSet()
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
            val childField = DSL.field(tojOOQName(getAliasedTableName(sourceTable), from))
            val parentField = DSL.field(tojOOQName(parentRelationship.target_collection, to))
            childField.eq(parentField)
        }
    )


    private const val ORDER_FIELD_SUFFIX = "_order_field"

    private fun getSelectOrderFields(request: QueryRequest): List<Field<*>> {
        val sortFields = translateIROrderByField(request, request.collection)
        return sortFields.map {
            // Use the qualified name (which includes the table name) to ensure uniqueness
            val field = it.`$field`()
            val qualifiedName = field.qualifiedName.toString().replace("\"", "")
            field.`as`(qualifiedName + ORDER_FIELD_SUFFIX)
        }
    }

    private fun getConcatOrderFields(request: QueryRequest): List<SortField<*>> {
        val sortFields = translateIROrderByField(request, request.collection)
        return sortFields.map {
            // Use the qualified name (which includes the table name) to ensure uniqueness
            val field = it.`$field`()
            val qualifiedName = field.qualifiedName.toString().replace("\"", "")
            val orderField = DSL.field(DSL.name(qualifiedName + ORDER_FIELD_SUFFIX))
            when (it.order) {
                SortOrder.ASC -> orderField.asc().nullsLast()
                SortOrder.DESC -> orderField.desc().nullsFirst()
                else -> orderField.asc().nullsLast()
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
                var currentTableName = sourceCollection

                // Process each relationship in path sequentially
                orderByElement.target.path.forEachIndexed { index, pathElem ->
                    val relationshipName = pathElem.relationship
                    val relChain = listOf(currentCollection, relationshipName)

                    if (!seenRelations.contains(relChain)) {
                        seenRelations.add(relChain)

                        val relationship = request.collection_relationships[relationshipName]
                            ?: throw Exception("Relationship not found")

                        val forwardJoinKeys = if (index + 1 < orderByElement.target.path.size) {
                            val nextPathElem = orderByElement.target.path[index + 1]
                            val nextRelationshipName = nextPathElem.relationship
                            val nextRelationship = request.collection_relationships[nextRelationshipName]
                                ?: throw Exception("Relationship '$nextRelationshipName' not found for next step")
                            nextRelationship.column_mapping.keys.map { sourceColumnInNextMapping ->
                                DSL.field(tojOOQName(sourceColumnInNextMapping))
                            }
                        } else {
                            emptyList()
                        }

                        when (relationship.relationship_type) {
                            RelationshipType.Object -> {
                                select.leftJoin(
                                    DSL.table(tojOOQName(relationship.target_collection))
                                ).on(
                                    mkSQLJoin2(
                                        rel = relationship,
                                        sourceCollection = currentTableName,
                                    )
                                )
                                // Update current table name for the next join
                                currentTableName = relationship.target_collection
                            }

                            RelationshipType.Array -> {
                                val aggregateTableAlias = "${relationshipName}_aggregate"
                                select.leftJoin(
                                    mkAggregateSubquery2(
                                        elem = orderByElement,
                                        relationship = relationship,
                                        whereCondition = DSL.noCondition(),
                                        forwardJoinKeys = forwardJoinKeys,
                                    ).asTable(
                                        DSL.name(aggregateTableAlias)
                                    )
                                ).on(
                                    mkSQLJoin2(
                                        rel = relationship,
                                        sourceCollection = currentTableName,
                                        targetTableNameTransform = { aggregateTableAlias }
                                    )
                                )
                                // Update current table name for the next join
                                currentTableName = aggregateTableAlias
                            }
                        }

                        // Add predicate condition for the path element
                        if (pathElem.predicate != Expression.And(emptyList())) {
                            select.where(
                                expressionToCondition(
                                    pathElem.predicate,
                                    request.copy(
                                        collection = relationship.target_collection,
                                    )
                                )
                            )
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
                    .eq(DSL.field(tojOOQName(targetTableFQN, targetColumn)))
            }
                    + rel.arguments.map { (targetColumn, argument) ->
                DSL.field(tojOOQName(sourceCollection, (argument as Argument.Column).name))
                    .eq(DSL.field(tojOOQName(targetTableFQN, targetColumn)))
            }
        )
    }


    fun mkAggregateSubquery2(
        elem: OrderByElement,
        relationship: Relationship,
        whereCondition: Condition,
        forwardJoinKeys: List<Field<*>> = emptyList(),
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
        ): List<Field<Any>> {
            return if (rel == null)
                emptyList()
            else if (rel.column_mapping.isNotEmpty())
                rel.column_mapping.values.map { DSL.field(tojOOQName(rel.target_collection, it)) }
            else if (rel.arguments.isNotEmpty())
                rel.arguments.keys.map { DSL.field(DSL.name(it)) }
            else emptyList()
        }

        val joinCols = mkJoinKeyFields2(relationship) + forwardJoinKeys

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

    private fun getTableName(collection: String): String {
        return collection.split('.').last()
    }

    private fun getAliasedTableName(collection: String): String {
        return getTableName(collection) + "_alias"
    }

}
