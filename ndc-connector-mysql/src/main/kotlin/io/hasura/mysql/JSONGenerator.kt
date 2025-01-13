package io.hasura.mysql

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.ir.Type
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

        val baseSelection = DSL.select(
            DSL.table(DSL.name(request.collection)).asterisk()
        ).select(getSelectOrderFields(request))
            .from(
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
        }
            .apply {
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
                                DSL.jsonArrayAgg(
                                    DSL.jsonObject(
                                        (request.query.fields ?: emptyMap()).map { (alias, field) ->
                                            when (field) {
                                                is ColumnField -> {
                                                    val columnField = DSL.field(DSL.name(field.column))
                                                    val (columnType, ndcScalar) = columnTypeTojOOQType(request.collection, field)
                                                    val castedField = castToSQLDataType(columnField, ndcScalar)
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

    private fun collectRequiredJoinTablesForWhereClause(
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

    private fun castToSQLDataType(field: Field<*>, type: NDCScalar): Field<*> {
        return when (type) {
            NDCScalar.INT64, NDCScalar.BIGINTEGER, NDCScalar.BIGDECIMAL -> 
                field.cast(SQLDataType.VARCHAR)
            NDCScalar.GEOMETRY, NDCScalar.GEOGRAPHY -> 
                DSL.jsonObject(field.cast(SQLDataType.VARCHAR))
            NDCScalar.BOOLEAN -> field.cast(SQLDataType.BOOLEAN)
            else -> field
        }
    }

    private fun ndcScalarTypeToSQLDataType(scalarType: NDCScalar): DataType<out Any> = when (scalarType) {
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

    private fun columnTypeTojOOQType(collection: String, field: ColumnField): Pair<org.jooq.DataType<out Any>, NDCScalar> {
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

                MySQLJDBCSchemaGenerator.mapScalarType(column.type, column.numeric_scale)
            }

            collectionIsNativeQuery -> {
                val nativeQuery = connectorConfig.nativeQueries[collection]
                    ?: error("Native query $collection not found in connector configuration")

                val column = nativeQuery.columns[field.column]
                    ?: error("Column ${field.column} not found in native query $collection")

                MySQLJDBCSchemaGenerator.mapScalarType(Type.extractBaseType(column), null)
            }

            else -> error("Collection $collection not found in connector configuration")
        }

        return Pair(ndcScalarTypeToSQLDataType(scalarType), scalarType)
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
