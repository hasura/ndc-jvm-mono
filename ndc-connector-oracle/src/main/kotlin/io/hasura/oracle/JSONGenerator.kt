package io.hasura.oracle

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Type
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType


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
    ): JSONObjectNullStep<*> {

        val baseSelection = DSL.select(
            DSL.table(DSL.name(request.collection)).asterisk()
        ).select(
            getSelectOrderFields(request)
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
            addJoinsRequiredForOrderByFields(this, request)
        }.apply {
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
            DSL.name(getAliasedTableName(request.collection))
        )

        return DSL.jsonObject(
            buildList {
                if (!request.query.fields.isNullOrEmpty()) {
                    add(
                        DSL.jsonEntry(
                            "rows",
                            DSL.select(
                                DSL.jsonArrayAgg(
                                    DSL.jsonObject(
                                        (request.query.fields ?: emptyMap()).map { (alias, field) ->
                                            when (field) {
                                                is ColumnField -> {
                                                    val field = DSL.field(
                                                        DSL.name(field.column),
                                                        columnTypeTojOOQType(request.collection, field)
                                                    )

                                                    DSL.jsonEntry(
                                                        alias,
                                                        // Oracle JSON functions convert DATE to ISO8601 format, which includes a timestamp
                                                        // We need to convert it to a date-only format to preserve the actual date value
                                                        //
                                                        // SEE: https://docs.oracle.com/en/database/oracle/oracle-database/23/adjsn/overview-json-generation.html
                                                        //      (Section: "Result Returned by SQL/JSON Generation Functions")
                                                        when (field.dataType) {
                                                            SQLDataType.DATE -> DSL.toChar(field, DSL.inline("YYYY-MM-DD HH24:MI:SS"))
                                                            SQLDataType.TIMESTAMP -> DSL.toChar(field, DSL.inline("YYYY-MM-DD HH24:MI:SS.FF6"))
                                                            SQLDataType.TIMESTAMPWITHTIMEZONE -> DSL.toChar(field, DSL.inline("YYYY-MM-DD HH24:MI:SS.FF6 TZR"))
                                                            else -> field
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
                                                                    DSL.jsonArray().returning(SQLDataType.CLOB)
                                                                )
                                                            ).returning(SQLDataType.CLOB)
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    ).returning(SQLDataType.CLOB)
                                ).orderBy(
                                    getConcatOrderFields(request)
                                ).returning(SQLDataType.CLOB)
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
                                ).returning(SQLDataType.CLOB)
                            ).from(
                                baseSelection
                            )
                        )
                    )
                }
            }
        ).returning(SQLDataType.CLOB) as JSONObjectNullStep<*>
    }
    
    
    fun collectRequiredJoinTablesForWhereClause(
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

    private fun columnTypeTojOOQType(collection: String, field: ColumnField): org.jooq.DataType<out Any> {
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

                OracleJDBCSchemaGenerator.mapScalarType(column.type, column.numeric_precision, column.numeric_scale)
            }

            collectionIsNativeQuery -> {
                val nativeQuery = connectorConfig.nativeQueries[collection]
                    ?: error("Native query $collection not found in connector configuration")

                val column = nativeQuery.columns[field.column]
                    ?: error("Column ${field.column} not found in native query $collection")

                OracleJDBCSchemaGenerator.mapScalarType(Type.extractBaseType(column), null, null)
            }

            else -> error("Collection $collection not found in connector configuration")
        }

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
            val childField = DSL.field(DSL.name(getAliasedTableName(sourceTable), from))
            val parentField = DSL.field(DSL.name(parentRelationship.target_collection, to))
            childField.eq(parentField)
        }
    )

    private fun getTableName(collection: String): String {
        return collection.split('.').last()
    }

    private fun getAliasedTableName(collection: String): String {
        return getTableName(collection) + "_alias"
    }

    private const val ORDER_FIELD_SUFFIX = "_order_field"

    private fun getSelectOrderFields(request: QueryRequest) : List<Field<*>>{
        val sortFields = translateIROrderByField(request, request.collection)
        return sortFields.map {  it.`$field`().`as`(it.name + ORDER_FIELD_SUFFIX) }
    }

    private fun getConcatOrderFields(request: QueryRequest) : List<SortField<*>>{
        val sortFields = translateIROrderByField(request, request.collection)
        return sortFields.map {
            val field = DSL.field(DSL.name(it.name + ORDER_FIELD_SUFFIX))
            when(it.order) {
                SortOrder.ASC -> field.asc().nullsLast()
                SortOrder.DESC -> field.desc().nullsFirst()
                else -> field.asc().nullsLast()
            }
        }
    }

}
