package io.hasura.snowflake

import io.hasura.ndc.app.services.ConnectorConfigurationLoader
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import org.gradle.internal.impldep.org.junit.platform.commons.util.FunctionUtils.where
import org.jooq.*
import org.jooq.Field
import org.jooq.impl.CustomField
import org.jooq.impl.DSL
import org.jooq.impl.DSL.jsonArrayAgg
import org.jooq.impl.DSL.orderBy
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
        return queryRequestToSQLInternal(request)
    }

    fun queryRequestToSQLInternal(
        request: QueryRequest,
        parentTable: String? = null,
        parentRelationship: Relationship? = null,
    ): SelectHavingStep<Record1<JSON>> {
        val requiredJoinTables = if (request.query.predicate != null) {
            collectRequiredJoinTablesForWhereClause(
                where = request.query.predicate!!,
                collectionRelationships = request.collection_relationships
            )
        } else {
            emptySet()
        }

        val referencedColumns = collectColumnsReferencedByRelationships(
            fields = request.query.fields ?: emptyMap(),
            collectionRelationships = request.collection_relationships
        )

        return DSL.select(
            DSL.jsonObject(
                DSL.jsonEntry(
                    "rows",
                    DSL.select(
                        DSL.jsonArrayAgg(
                            DSL.jsonObject(
                                (request.query.fields ?: emptyMap()).map { (alias, field) ->
                                    when (field) {
                                        is ColumnField -> {
                                            DSL.jsonEntry(
                                                alias,
                                                DSL.field(DSL.name(field.column))
                                            )
                                        }

                                        is IRField.RelationshipField -> {
                                            val relationship =
                                                request.collection_relationships[field.relationship]
                                                    ?: error("Relationship ${field.relationship} not found")

                                            val subQuery = queryRequestToSQLInternal(
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
                            )
                        ).orderBy(
                            DSL.field(DSL.name("rn"))
                        )
                    ).from(
                        DSL.select(
                            buildList<org.jooq.Field<*>> {
                                if (request.query.fields != null) {
                                    addAll(
                                        request.query.fields!!.filter { (_, field) -> field is ColumnField }
                                            .map { (alias, field) ->
                                                field as ColumnField
                                                DSL.field(DSL.name(request.collection, field.column)).`as`(alias)
                                            }
                                    )
                                }
                                if (referencedColumns.isNotEmpty()) {
                                    addAll(
                                        referencedColumns.map {
                                            DSL.field(DSL.name(request.collection, it))
                                        }
                                    )
                                }
                                add(
                                    DSL.rowNumber().over(
                                        DSL.partitionBy(
                                            parentRelationship?.column_mapping?.values?.map {
                                                DSL.field(DSL.name(parentRelationship.target_collection, it))
                                            } ?: emptyList()
                                        ).orderBy(
                                            translateIROrderByField(
                                                orderBy = request.query.order_by,
                                                currentCollection = getTableName(request.collection),
                                                relationships = request.collection_relationships
                                            )
                                        )
                                    ).`as`(
                                        DSL.name("rn")
                                    )
                                )
                            }
                        ).from(
                            run<Table<Record>> {
                                val table = DSL.table(DSL.name(request.collection))
                                if (request.query.predicate == null) {
                                    table
                                } else {
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
                        }.asTable(
                            DSL.name(getTableName(request.collection))
                        )
                    ).apply {
                        if (parentRelationship != null) {
                            where(
                               parentRelationship.column_mapping.map { (from, to) ->
                                    DSL.field(DSL.name(to)).eq(
                                       DSL.field(DSL.name(getTableName(parentTable!!), from))
                                    )
                                }
                            )
                        }
                        if (request.query.offset != null) {
                            where(DSL.field(DSL.name("rn")).gt(request.query.offset))
                        }
                        if (request.query.limit != null) {
                            val offset = request.query.offset ?: 0
                            val upperBound = offset + request.query.limit!!
                            where(DSL.field(DSL.name("rn")).le(upperBound))
                        }
                    }
                )
            )
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

    // Returns all the columns in a parent table which are referenced
    // by fields of type "relationship" and needed to join the two tables
    //
    // If a join column is already present in the requested fields, we skip it to avoid duplication
    fun collectColumnsReferencedByRelationships(
        fields: Map<String, IRField>,
        collectionRelationships: Map<String, Relationship>
    ): Set<String> {
        val columnFields = fields.values
            .filterIsInstance<ColumnField>()
            .map { it.column }.toSet()

        return fields.values
            .filterIsInstance<IRField.RelationshipField>()
            .mapNotNull { field ->
                collectionRelationships[field.relationship]
                    ?.column_mapping
                    ?.values
                    ?.filterNot(columnFields::contains)
            }
            .flatten()
            .toSet()
    }

    private fun columnTypeTojOOQType(collection: String, field: ColumnField): org.jooq.DataType<out Any> {
        val connectorConfig = ConnectorConfigurationLoader.config

        val table = connectorConfig.tables.find { it.tableName == collection }
            ?: error("Table $collection not found in connector configuration")

        val column = table.columns.find { it.name == field.column }
            ?: error("Column ${field.column} not found in table $collection")

        val scalarType = SnowflakeJDBCSchemaGenerator.mapScalarType(column.type, column.numeric_scale)
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
