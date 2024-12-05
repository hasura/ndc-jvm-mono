package io.hasura.ndc.sqlgen

import io.hasura.ndc.ir.*
import org.jooq.Condition
import org.jooq.impl.DSL
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
                    .eq(DSL.field(DSL.name(listOf(targetTableFQN, targetColumn))))
            }
                    + rel.arguments.map { (targetColumn, argument) ->
                DSL.field(DSL.name(listOf(sourceCollection, (argument as Argument.Column).name)))
                    .eq(DSL.field(DSL.name(listOf(targetTableFQN, targetColumn))))
            }
        )
    }

    fun buildComparison(
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
            ApplyBinaryComparisonOperator.IN -> DSL.nullCondition()
            ApplyBinaryComparisonOperator.IS_NULL -> col.isNull
            ApplyBinaryComparisonOperator.LIKE -> col.like(value as Field<String>)
            ApplyBinaryComparisonOperator.CONTAINS -> col.contains(value as Field<String>)
        }
    }

    private fun getCollectionForCompCol(
        col: ComparisonColumn,
        request: QueryRequest
    ): String {
        // Make sure to handle the case when the path references a related table
        return when (col) {
            is ComparisonColumn.RootCollectionColumn -> request.root_collection
            is ComparisonColumn.Column -> {
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
            is Argument.Column -> ComparisonValue.ColumnComp(ComparisonColumn.RootCollectionColumn(arg.name))
        }
        val e = Expression.ApplyBinaryComparison(
            ApplyBinaryComparisonOperator.EQ,
            ComparisonColumn.Column(argument.key, emptyList()),
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
                val column = DSL.field(
                    DSL.name(
                        splitCollectionName(getCollectionForCompCol(e.column, request)) + e.column.name
                    )
                )
                val comparisonValue = when (val v = e.value) {
                    is ComparisonValue.ColumnComp -> {
                        val col = splitCollectionName(getCollectionForCompCol(v.column, request))
                        DSL.field(DSL.name(col + v.column.name))
                    }

                    is ComparisonValue.ScalarComp ->
                        if(e.operator == ApplyBinaryComparisonOperator.IN)
                            return handleInComp(column, v)
                         else DSL.inline(v.value)

                    is ComparisonValue.VariableComp -> DSL.field(DSL.name(listOf("vars", v.name)))
                }
                return buildComparison(column, e.operator, comparisonValue)
            }

            is Expression.ApplyUnaryComparison -> {
                val column = DSL.field(DSL.name(splitCollectionName(request.collection) + e.column))
                when (e.operator) {
                    ApplyUnaryComparisonOperator.IS_NULL -> column.isNull
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

    fun handleInComp(column: Field<Any>, value: ComparisonValue.ScalarComp): Condition {
        return when (val scalarValue = value.value) {
            is List<*> -> {
                if (scalarValue.isEmpty()) DSL.falseCondition()
                else column.`in`(scalarValue.map { DSL.inline(it) })
            }
            // Handle non-array scalar value
            else -> column.eq(DSL.inline(scalarValue))
        }
    }

    fun splitCollectionName(collectionName: String): List<String> {
        return collectionName.split(".")
    }

}
