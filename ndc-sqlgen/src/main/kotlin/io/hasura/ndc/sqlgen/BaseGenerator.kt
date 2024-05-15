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

    abstract fun buildComparison(col: Field<Any>, operator: ApplyBinaryComparisonOperator, value: Field<Any>): Condition

    private fun getCollectionForCompCol(
        col: ComparisonColumn,
        request: QueryRequest
    ): String {
        return when (col) {
            // this is wrong, consider passing in root collection?
            is ComparisonColumn.RootCollectionColumn -> request.root_collection
            is ComparisonColumn.Column -> {
                if (col.path.isNotEmpty()) {
                    // TODO: is there a chance that there would be more than one rel in this context?
                    val relName = col.path.map { it.relationship }.first()
                    request.collection_relationships[relName]!!.target_collection
                } else request.collection
            }
        }
    }

    fun argumentToCondition(request: QueryRequest, argument: Map.Entry<String, Argument>, overrideCollection: String)
        = argumentToCondition(request.copy(collection = overrideCollection), argument)
    fun argumentToCondition(request: QueryRequest, argument: Map.Entry<String, Argument>) : Condition {
        val compVal = when (val arg = argument.value) {
            is Argument.Variable -> ComparisonValue.VariableComp(arg.name)
            is Argument.Literal -> ComparisonValue.ScalarComp(arg.value)
            is Argument.Column -> ComparisonValue.ColumnComp(ComparisonColumn.RootCollectionColumn(arg.name))
        }
        val e = Expression.ApplyBinaryComparison(
            ApplyBinaryComparisonOperator.Equal,
            ComparisonColumn.Column(argument.key, emptyList()),
            compVal
        )
        return expressionToCondition(e, request)
    }

    // override request collection for expressionToCondition evaluation
    fun expressionToCondition( e: Expression, request: QueryRequest, overrideCollection: String)
        = expressionToCondition(e, request.copy(collection = overrideCollection))


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
            // The negation of a single subexpression
            is Expression.Not -> DSL.not(expressionToCondition(e.expression,request))

            // A conjunction of several subexpressions
            is Expression.And -> when (e.expressions.size) {
                0 -> DSL.trueCondition()
                else -> DSL.and(e.expressions.map { expressionToCondition(it,request) })
            }

            // A disjunction of several subexpressions
            is Expression.Or -> when (e.expressions.size) {
                0 -> DSL.falseCondition()
                else -> DSL.or(e.expressions.map { expressionToCondition(it,request) })
            }

            // Test the specified column against a single value using a particular binary comparison operator
            is Expression.ApplyBinaryComparison -> {

                val column = DSL.field(
                    DSL.name(
                        listOf(
                            getCollectionForCompCol(e.column, request),
                            e.column.name
                        )
                    )
                )
                val comparisonValue = when (val v = e.value) {
                    is ComparisonValue.ColumnComp -> {
                        val col = getCollectionForCompCol(v.column, request)
                        DSL.field(DSL.name(listOf(col, v.column.name)))
                    }

                    is ComparisonValue.ScalarComp -> DSL.inline(v.value)
                    is ComparisonValue.VariableComp -> DSL.field(DSL.name(listOf("vars", v.name)))
                }
                return buildComparison(column, e.operator, comparisonValue)
            }

            // Test the specified column against a particular unary comparison operator
            is Expression.ApplyUnaryComparison -> {
                val baseCond = run {
                    val column = DSL.field(DSL.name(listOf(request.collection, e.column)))
                    when (e.operator) {
                        ApplyUnaryComparisonOperator.IS_NULL -> column.isNull
                    }
                }
                baseCond
            }

            // Test the specified column against an array of values using a particular binary comparison operator
            is Expression.ApplyBinaryArrayComparison -> {
                val baseCond = run {
                    val column = DSL.field(
                        DSL.name(
                            listOf(
                                getCollectionForCompCol(e.column, request),
                                e.column.name
                            )
                        )
                    )
                    when (e.operator) {
                        ApplyBinaryArrayComparisonOperator.IN -> {
                            when {
                                // Generate "IN (SELECT NULL WHERE 1 = 0)" for easier debugging
                                e.values.isEmpty() -> column.`in`(
                                    DSL.select(DSL.nullCondition())
                                        .where(DSL.inline(1).eq(DSL.inline(0)))
                                )

                                else -> {

                                    // TODO: swtich map to local context as map will need to be separate for the select column comparisions
                                    // TODO: is it safe to assume that cols will all be from one collections?
                                    column.`in`(DSL.list(e.values.map {
                                        when (it) {
                                            is ComparisonValue.ScalarComp -> DSL.inline(it.value)
                                            is ComparisonValue.VariableComp -> DSL.field(DSL.name(listOf("vars", it.name)))
                                            is ComparisonValue.ColumnComp -> {
                                                val col = getCollectionForCompCol(it.column, request)
                                                DSL.field(DSL.name(listOf(col, it.column.name)))
                                            }
                                        }
                                    }))
                                }
                            }
                        }
                    }
                }
                baseCond
            }

            // Test if a row exists that matches the where subexpression in the specified table (in_table)
            //
            // where (
            //   exists (
            //     select 1 "one"
            //     from "AwsDataCatalog"."chinook"."album"
            //     where (
            //         "AwsDataCatalog"."chinook"."album"."artistid" = "artist_base_fields_0"."artistid"
            //         and "AwsDataCatalog"."chinook"."album"."title" = 'For Those About To Rock We Salute You'
            //         and exists (
            //           select 1 "one"
            //           from "AwsDataCatalog"."chinook"."track"
            //           where (
            //               "AwsDataCatalog"."chinook"."track"."albumid" = "albumid"
            //               and "AwsDataCatalog"."chinook"."track"."name" = 'For Those About To Rock (We Salute You)'
            //           )
            //         )
            //     )
            //   )
            // )
            is Expression.Exists -> {
                when (val inTable = e.in_collection) {
                    // The table is related to the current table via the relationship name specified in relationship
                    // (this means it should be joined to the current table via the relationship)
                    is ExistsInCollection.Related -> {
                        val relOrig = request.collection_relationships[inTable.relationship] ?: throw Exception("Exists relationship not found")
                        val rel = relOrig.copy(arguments = relOrig.arguments + inTable.arguments)
                        DSL.exists(
                            DSL
                                .selectOne()
                                .from(
                                    DSL.table(DSL.name(rel.target_collection))
                                )
                                .where(
                                    DSL.and(
                                        listOf(
                                            expressionToCondition(
                                                e.where,
                                                request,
                                                rel.target_collection
                                            )
                                        ) +
                                        rel.column_mapping.map { (sourceCol, targetCol) ->
                                            DSL.field(DSL.name(listOf(request.collection, sourceCol)))
                                                .eq(DSL.field(DSL.name(listOf(rel.target_collection, targetCol))))
                                        } + rel.arguments.map {argumentToCondition(request, it, rel.target_collection) }
                                    )
                                )
                        )
                    }

                    // The table specified by table is unrelated to the current table and therefore is not explicitly joined to the current table
                    // (this means it should be joined to the current table via a subquery)
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
                                    DSL.table(DSL.name(inTable.collection))
                                )
                                .where(
                                    listOf(
                                        expressionToCondition(
                                            e.where,
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
}
