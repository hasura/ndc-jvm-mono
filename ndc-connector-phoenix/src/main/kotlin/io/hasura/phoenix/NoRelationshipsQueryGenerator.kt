package io.hasura.phoenix

import io.hasura.ndc.app.services.ConnectorConfigurationLoader
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType


object NoRelationshipsQueryGenerator : BaseQueryGenerator() {
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
        return queryRequestToSQLInternal(request)
    }

    private fun queryRequestToSQLInternal(
        request: QueryRequest
    ) = run {
        val stmt = DSL.select(
            getQueryColumnFields(request.query.fields ?: emptyMap()).map { (alias, field) ->
                DSL.field(DSL.name(getTableName(request.collection), field.column)).`as`(DSL.name(alias))
            } + getAggregateFields(request).map { (alias, aggregate) ->
                getAggregatejOOQFunction(aggregate).`as`(DSL.name(alias))
            }
        ).from(
            DSL.table(DSL.name(request.collection))
        ).apply {
            if (request.query.predicate != null) {
                where(expressionToCondition(request.query.predicate!!, request))
            }
            if (request.query.order_by != null) {
                orderBy(
                    translateIROrderByField(request)
                )
            }
            if (request.query.limit != null) {
                limit(request.query.limit)
            }
            if (request.query.offset != null) {
                offset(request.query.offset)
            }
        }

        when {
            request.variables.isNullOrEmpty() -> stmt
            else -> {
                // select "x"."CITY", "x"."STATE", "x"."POPULATION"
                //from (
                //  select
                //    "US_POPULATION"."CITY" as "CITY",
                //    "US_POPULATION"."STATE" as "STATE",
                //    "US_POPULATION"."POPULATION" as "POPULATION"
                //  from "US_POPULATION"
                //  join (
                //    select 'NY' as "STATE"
                //    union all
                //    select 'CA' as "STATE"
                //  ) as "$variables"
                //  on "US_POPULATION"."STATE" = "$variables"."STATE"
                //) as "x"
                val vars = request.variables!!

                val variableNames = vars.flatMap { it.keys }.distinct()
                val variablesSubquery = vars
                    .mapIndexed { idx, variable ->
                        DSL.select(
                            variableNames
                                .map { DSL.value(variable[it]).`as`(DSL.name(it)) }
                                .plus(DSL.value(idx).`as`(DSL.name("idx")))
                        )
                    }
                    .reduce { acc, table ->
                        acc.unionAll(table) as SelectSelectStep<Record>
                    }
                    .asTable("vars")


                stmt.join(
                    variablesSubquery
                ).on(
                    mkJoinWhereClause(
                        request.collection,
                        Relationship(
                            target_collection = "vars",
                            column_mapping = variableNames.associateWith { it },
                            relationship_type = RelationshipType.Object,
                            arguments = emptyMap()
                        )
                    )
                )
            }
        }
    }

    private fun columnTypeTojOOQType(collection: String, field: ColumnField): DataType<out Any> {
        val connectorConfig = ConnectorConfigurationLoader.config

        val table = connectorConfig.tables.find { it.tableName == collection }
            ?: error("Table $collection not found in connector configuration")

        val column = table.columns.find { it.name == field.column }
            ?: error("Column ${field.column} not found in table $collection")

        val scalarType = PhoenixJDBCSchemaGenerator.mapScalarType(column.type, column.numeric_scale)
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
        parentRelationship: Relationship,
        parentTableAlias: String? = null
    ) = DSL.and(
        parentRelationship.column_mapping.map { (from, to) ->
            val childField = DSL.field(DSL.name(getTableName(sourceTable), from))
            val parentField = DSL.field(DSL.name(parentTableAlias ?: parentRelationship.target_collection, to))
            childField.eq(parentField)
        }
    )

    private fun getTableName(collection: String): String {
        return collection.split('.').last()
    }

}
