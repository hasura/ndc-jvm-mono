package io.hasura.oracle

import io.hasura.ndc.ir.*
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
            is ApplyBinaryComparisonOperator.Equal -> col.eq(value)
            is ApplyBinaryComparisonOperator.Other -> when (operator.name) {
                LessThan.getJsonName() -> col.lt(value)
                LessThanOrEqual.getJsonName() -> col.le(value)
                GreaterThan.getJsonName() -> col.gt(value)
                GreaterThanOrEqual.getJsonName() -> col.ge(value)
                Contains.getJsonName() -> col.contains(value)
                Like.getJsonName() -> col.like(value as Field<String>)
                else -> throw Exception("Invalid comparison operator")
            }

            else -> throw Exception("Invalid comparison operator")
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
                                                is IRField.ColumnField -> {
                                                    DSL.jsonEntry(
                                                        alias,
                                                        DSL.field(DSL.name(field.column))
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
                                ).returning(SQLDataType.CLOB)
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
            DSL.selectFrom(
                DSL.table(DSL.name(request.collection))
            ).apply {
                if (request.query.where != null) {
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
                if (request.query.limit != null) {
                    limit(request.query.limit)
                }
                if (request.query.offset != null) {
                    offset(request.query.offset)
                }
            }.asTable(DSL.name(request.collection))
        ).groupBy(
            DSL.nullCondition()
        )
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
            val childField = DSL.field(DSL.name(sourceTable, from))
            val parentField = DSL.field(DSL.name(parentRelationship.target_collection, to))
            childField.eq(parentField)
        }
    )

}
