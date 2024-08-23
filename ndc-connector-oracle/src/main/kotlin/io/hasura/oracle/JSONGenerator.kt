package io.hasura.oracle

import io.hasura.ndc.app.services.ConnectorConfigurationLoader
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType


object JsonQueryGenerator : BaseQueryGenerator() {
    override fun buildComparison(
        col: Field<Any>,
        operator: ApplyBinaryComparisonOperator,
        value: Field<Any>
    ): Condition {
        return when (operator) {
            ApplyBinaryComparisonOperator.EQ -> col.eq(value)
            ApplyBinaryComparisonOperator.GT -> col.gt(value)
            ApplyBinaryComparisonOperator.GTE -> col.ge(value)
            ApplyBinaryComparisonOperator.LT -> col.lt(value)
            ApplyBinaryComparisonOperator.LTE -> col.le(value)
            ApplyBinaryComparisonOperator.IN -> col.`in`(value)
            ApplyBinaryComparisonOperator.IS_NULL -> col.isNull
            ApplyBinaryComparisonOperator.LIKE -> col.like(value as Field<String>)
        }
    }

    override fun forEachQueryRequestToSQL(request: QueryRequest): Select<*> {
        TODO("Not yet implemented")
    }

    override fun queryRequestToSQL(request: QueryRequest): Select<*> {
        return queryRequestToSQLInternal2(request)
    }

    fun queryRequestToSQLInternal2(
        request: QueryRequest,
        parentTable: String? = null,
        parentRelationship: Relationship? = null,
    ): SelectHavingStep<Record1<JSON>> {
        val isRootQuery = parentRelationship == null

        return DSL.select(
            DSL.jsonObject(
                buildList {
                    if (!request.query.fields.isNullOrEmpty()) {
                        add(
                            DSL.jsonEntry(
                                "rows",
                                DSL.jsonArrayAgg(
                                    DSL.jsonObject(
                                        (request.query.fields ?: emptyMap()).map { (alias, field) ->
                                            when (field) {
                                                is ColumnField -> {
                                                    DSL.jsonEntry(
                                                        alias,
                                                        DSL.field(
                                                            DSL.name(field.column),
                                                            columnTypeTojOOQType(request.collection, field)
                                                        )
                                                    )
                                                }

                                                is IRField.RelationshipField -> {
                                                    val relationship =
                                                        request.collection_relationships[field.relationship]
                                                            ?: error("Relationship ${field.relationship} not found")

                                                    val subQuery = queryRequestToSQLInternal2(
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
                                                        DSL.select(
                                                            subQuery.asField<Any>(alias)
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    ).returning(SQLDataType.CLOB)
                                ).apply {
                                    if (request.query.order_by != null) {
                                        orderBy(
                                            translateIROrderByField(
                                                orderBy = request.query.order_by,
                                                currentCollection = getTableName(request.collection),
                                                relationships = request.collection_relationships
                                            )
                                        )
                                    }
                                }.returning(SQLDataType.CLOB)
                            )
                        )
                    }
                    if (!request.query.aggregates.isNullOrEmpty()) {
                        add(
                            DSL.jsonEntry(
                                "aggregates",
                                DSL.jsonObject(
                                    (request.query.aggregates ?: emptyMap()).map { (alias, aggregate) ->
                                        DSL.jsonEntry(
                                            alias,
                                            getAggregatejOOQFunction(aggregate)
                                        )
                                    }
                                )
                            )
                        )
                    }
                }
            ).returning(SQLDataType.CLOB).let {
                when {
                    isRootQuery -> DSL.jsonArrayAgg(it).returning(SQLDataType.CLOB)
                    else -> it
                }
            }
        ).from(
            DSL.select(
                DSL.table(DSL.name(request.collection))
                    .asterisk()
            ).from(
                run<Table<Record>> {
                    val table = DSL.table(DSL.name(request.collection))
                    if (request.query.predicate == null) {
                        table
                    } else {
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
        ).groupBy(
            DSL.nullCondition()
        )
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

    private fun columnTypeTojOOQType(collection: String, field: ColumnField): org.jooq.DataType<out Any> {
        val connectorConfig = ConnectorConfigurationLoader.config

        val table = connectorConfig.tables.find { it.tableName == collection }
            ?: error("Table $collection not found in connector configuration")

        val column = table.columns.find { it.name == field.column }
            ?: error("Column ${field.column} not found in table $collection")

        val scalarType = OracleJDBCSchemaGenerator.mapScalarType(column.type, column.numeric_scale)
        return when (scalarType) {
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
