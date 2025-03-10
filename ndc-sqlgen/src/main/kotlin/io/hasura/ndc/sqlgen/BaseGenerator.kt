package io.hasura.ndc.sqlgen

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.*
import org.jooq.Condition
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.Field

sealed interface BaseGenerator {

    fun mkSQLJoin(
        rel: Relationship,
        sourceCollection: String,
        targetTableNameTransform: (String) -> String = { it },
    ): Condition {
        val targetTableFQN = targetTableNameTransform(rel.target_collection)
        return DSL.and(
            rel.column_mapping.map { (sourceColumn, targetColumn) ->
                DSL.field(DSL.name(listOf(sourceCollection, sourceColumn)))
                    .eq(DSL.field(DSL.name(targetTableFQN.split(".") + targetColumn)))
            }
                    + rel.arguments.map { (targetColumn, argument) ->
                DSL.field(DSL.name(listOf(sourceCollection, (argument as Argument.Column).name)))
                    .eq(DSL.field(DSL.name(targetTableFQN.split(".") + targetColumn)))
            }
        )
    }

    fun buildComparison(
        col: Field<Any>,
        operator: ApplyBinaryComparisonOperator,
        listVal: List<Field<Any>>,
        columnType: NDCScalar?
    ): Condition {
        if (operator != ApplyBinaryComparisonOperator.IN && listVal.size != 1) {
            error("Only the IN operator supports multiple values")
        }

        // unwrap single value for use in all but the IN operator
        // OR return falseCondition if listVal is empty
        val singleVal = listVal.firstOrNull() ?: return DSL.falseCondition()
        val castedValue = castValue(singleVal, columnType)

        return when (operator) {
            ApplyBinaryComparisonOperator.EQ -> col.eq(castedValue)
            ApplyBinaryComparisonOperator.GT -> col.gt(castedValue)
            ApplyBinaryComparisonOperator.GTE -> col.ge(castedValue)
            ApplyBinaryComparisonOperator.LT -> col.lt(castedValue)
            ApplyBinaryComparisonOperator.LTE -> col.le(castedValue)
            ApplyBinaryComparisonOperator.IN -> col.`in`(listVal.map { castValue(it, columnType) })
            ApplyBinaryComparisonOperator.IS_NULL -> col.isNull
            ApplyBinaryComparisonOperator.LIKE -> col.like(singleVal as Field<String>)
            ApplyBinaryComparisonOperator.CONTAINS -> col.contains(singleVal as Field<String>)
        }
    }

    fun castValue(value: Any, scalarType: NDCScalar?): Any {
        return when (scalarType) {
            NDCScalar.TIMESTAMPTZ -> DSL.cast(value, SQLDataType.TIMESTAMPWITHTIMEZONE)
            NDCScalar.TIMESTAMP -> DSL.cast(value, SQLDataType.TIMESTAMP)
            NDCScalar.DATE -> DSL.cast(value, SQLDataType.DATE)
            else -> value
        }
    }

    fun getColumnType(col: ComparisonTarget, request: QueryRequest): NDCScalar? {
        val connectorConfig = ConnectorConfiguration.Loader.config
        val collection = getCollectionForCompCol(col, request)
        val collectionIsTable = connectorConfig.tables.any { it.tableName == collection }
        val collectionIsNativeQuery = connectorConfig.nativeQueries.containsKey(collection)
        val columnType =  when {
            collectionIsTable -> {
                val table = connectorConfig.tables.find { it.tableName == collection }
                    ?: error("Table $collection not found in connector configuration")

                val column = table.columns.find { it.name == col.name }
                    ?: error("Column ${col.name} not found in table $collection")

                column.type
            }

            collectionIsNativeQuery -> {
                val nativeQuery = connectorConfig.nativeQueries[collection]
                    ?: error("Native query $collection not found in connector configuration")

                val column = nativeQuery.columns[col.name]
                    ?: error("Column ${col.name} not found in native query $collection")

                Type.extractBaseType(column)
            }

            else -> error("Collection $collection not found in connector configuration")
        }

        return when {
          columnType == "DATE" -> NDCScalar.DATE
          columnType.contains("TIMESTAMP") && !columnType.contains("TIME ZONE") -> NDCScalar.TIMESTAMP
          columnType.contains("TIMESTAMP") && columnType.contains("TIME ZONE") -> NDCScalar.TIMESTAMPTZ
          else -> null
        }
    }

    private fun getCollectionForCompCol(
        col: ComparisonTarget,
        request: QueryRequest
    ): String {
        // Make sure to handle the case when the path references a related table
        return when (col) {
            is ComparisonTarget.RootCollectionColumn -> request.root_collection
            is ComparisonTarget.Column -> {
                if (col.path.isNotEmpty()) {
                    // Traverse the relationship path to get to the current collection name
                    val targetCollection = col.path.fold("") { acc, pathElement ->
                        val rel = request.collection_relationships[pathElement.relationship]
                            ?: throw Exception("Relationship not found")
                        rel.target_collection
                    }
                    targetCollection
                } else {
                    request.collection
                }
            }
        }
    }

    fun argumentToCondition(
        request: QueryRequest,
        argument: Map.Entry<String, Argument>,
        overrideCollection: String
    ) = argumentToCondition(request.copy(collection = overrideCollection), argument)

    fun argumentToCondition(request: QueryRequest, argument: Map.Entry<String, Argument>): Condition {
        val compVal = when (val arg = argument.value) {
            is Argument.Variable -> ComparisonValue.VariableComp(arg.name)
            is Argument.Literal -> ComparisonValue.ScalarComp(arg.value)
            is Argument.Column -> ComparisonValue.ColumnComp(ComparisonTarget.RootCollectionColumn(arg.name))
        }
        val e = Expression.ApplyBinaryComparison(
            ApplyBinaryComparisonOperator.EQ,
            ComparisonTarget.Column(argument.key, emptyList()),
            compVal
        )
        return expressionToCondition(e, request)
    }

    // override request collection for expressionToCondition evaluation
    fun expressionToCondition(e: Expression, request: QueryRequest, overrideCollection: String) =
        expressionToCondition(e, request.copy(collection = overrideCollection))


    // Convert a WHERE-like expression IR into a JOOQ Condition
    // Used for both "where" expressions and things like "post-insert check" expressions
    // Requires 3 things:
    // 1. The current table alias
    // 2. The relation graph for the request
    // 3. The actual Expression IR object to convert
    fun expressionToCondition(
        e: Expression,
        request: QueryRequest
    ): Condition {

        return when (e) {
            is Expression.Not -> DSL.not(expressionToCondition(e.expression, request))

            is Expression.And -> when (e.expressions.size) {
                0 -> DSL.trueCondition()
                else -> DSL.and(e.expressions.map { expressionToCondition(it, request) })
            }

            is Expression.Or -> when (e.expressions.size) {
                0 -> DSL.falseCondition()
                else -> DSL.or(e.expressions.map { expressionToCondition(it, request) })
            }

            is Expression.ApplyBinaryComparison -> {
                val columnType = getColumnType(e.column, request)
                val column = DSL.field(
                    DSL.name(
                        splitCollectionName(getCollectionForCompCol(e.column, request)) + e.column.name
                    )
                )
                val comparisonValue = when (val v = e.value) {
                    is ComparisonValue.ColumnComp -> {
                        val col = splitCollectionName(getCollectionForCompCol(v.column, request))
                        listOf(DSL.field(DSL.name(col + v.column.name)))
                    }

                    is ComparisonValue.ScalarComp ->
                        when (val scalarValue = v.value) {
                            is List<*> -> (scalarValue as List<Any>).map { DSL.inline(it) }
                            else -> listOf(DSL.inline(scalarValue))
                        }

                    is ComparisonValue.VariableComp -> listOf(DSL.field(DSL.name(listOf("vars", v.name))))
                }
                return buildComparison(column, e.operator, comparisonValue, columnType)
            }

            is Expression.ApplyUnaryComparison -> {
                val field = when (e.column) {
                    is ComparisonTarget.Column -> DSL.field(
                        DSL.name(
                            splitCollectionName(getCollectionForCompCol(e.column, request)) + e.column.name
                        )
                    )

                    is ComparisonTarget.RootCollectionColumn -> DSL.field(
                        DSL.name(
                            splitCollectionName(request.collection) + e.column.name
                        )
                    )
                }
                when (e.operator) {
                    ApplyUnaryComparisonOperator.IS_NULL -> field.isNull
                }
            }

            is Expression.Exists -> {
                when (val inTable = e.in_collection) {
                    is ExistsInCollection.Related -> {
                        val relOrig = request.collection_relationships[inTable.relationship]
                            ?: throw Exception("Exists relationship not found")
                        val rel = relOrig.copy(arguments = relOrig.arguments + inTable.arguments)
                        DSL.exists(
                            DSL
                                .selectOne()
                                .from(
                                    DSL.table(DSL.name(splitCollectionName(rel.target_collection)))
                                )
                                .where(
                                    DSL.and(
                                        listOf(
                                            expressionToCondition(
                                                e.predicate,
                                                request,
                                                rel.target_collection
                                            )
                                        ) +
                                                rel.column_mapping.map { (sourceCol, targetCol) ->
                                                    DSL.field(DSL.name(splitCollectionName(request.collection) + sourceCol))
                                                        .eq(DSL.field(DSL.name(splitCollectionName(rel.target_collection) + targetCol)))
                                                } + rel.arguments.map {
                                            argumentToCondition(
                                                request,
                                                it,
                                                rel.target_collection
                                            )
                                        }
                                    )
                                )
                        )
                    }

                    is ExistsInCollection.Unrelated -> {
                        val condition = mkSQLJoin(
                            Relationship(
                                target_collection = inTable.collection,
                                arguments = inTable.arguments,
                                column_mapping = emptyMap(),
                                relationship_type = RelationshipType.Array
                            ),
                            request.collection
                        )
                        DSL.exists(
                            DSL
                                .selectOne()
                                .from(
                                    DSL.table(DSL.name(splitCollectionName(inTable.collection)))
                                )
                                .where(
                                    listOf(
                                        expressionToCondition(
                                            e.predicate,
                                            request,
                                            inTable.collection
                                        ), condition
                                    )
                                )
                        )
                    }
                }
            }
        }
    }

    fun splitCollectionName(collectionName: String): List<String> {
        return collectionName.split(".")
    }

}
