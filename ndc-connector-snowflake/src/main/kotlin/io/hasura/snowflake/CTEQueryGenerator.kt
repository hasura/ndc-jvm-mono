package io.hasura

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.NDCScalar
import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.extensions.isVariablesRequest
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.ir.Field.ColumnField
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import io.hasura.ndc.sqlgen.BaseGenerator
import io.hasura.ndc.sqlgen.BaseQueryGenerator.Companion.INDEX
import io.hasura.ndc.sqlgen.BaseQueryGenerator.Companion.ROWS_AND_AGGREGATES
import io.hasura.ndc.sqlgen.DatabaseType.SNOWFLAKE
import io.hasura.snowflake.SnowflakeJDBCSchemaGenerator
import org.jooq.*
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType


object SnowflakeDSL {
    fun rlike(col: Field<Any>, field: Field<String>): Condition = DSL.condition("? rlike(?)", col, field)
}

object CTEQueryGenerator : BaseQueryGenerator() {
    override fun queryRequestToSQL(
        request: QueryRequest
    ): Select<*> {
           return buildCTEs(request)
                .select(DSL.jsonArrayAgg(DSL.field(DSL.name(listOf("data", ROWS_AND_AGGREGATES)))))
                .from(buildSelections(request).asTable("data"))
    }


    override fun forEachQueryRequestToSQL(request: QueryRequest): Select<*> {
        return buildCTEs(request, listOf(buildVarsCTE(request)))
            .select(
                DSL.jsonArrayAgg(
                    DSL.coalesce(
                        DSL.field(DSL.name(listOf("data", ROWS_AND_AGGREGATES))) as Field<*>,
                        DSL.jsonObject("rows", DSL.jsonArray())
                    )
                )
                    .orderBy(DSL.field(DSL.name(listOf(VARS, INDEX))))
            )
            .from(buildSelections(request).asTable("data"))
            .rightJoin(DSL.name(VARS))
            .on(
                DSL.field(DSL.name("data", INDEX))
                    .eq(DSL.field(DSL.name(VARS, INDEX)))
            )
    }

    private fun buildCTEs(request: QueryRequest, varCTE: List<CommonTableExpression<*>> = emptyList()): WithStep {
        val withStep = mkNativeQueryCTEs(request)
            .with(varCTE)
            .with(forEachQueryLevelRecursively(request, CTEQueryGenerator::buildCTE).distinct())

        return withStep
    }

    private fun getCollectionAsjOOQName(collection: String): Name {
        return DSL.name(collection.split("."))
    }
    private fun rewritePathComparisonsToExists(expression: Expression?): Expression? {
        fun rewrite(e: Expression): Expression {
            return when (e) {
                is Expression.And -> Expression.And(e.expressions.map { rewrite(it) })
                is Expression.Or -> Expression.Or(e.expressions.map { rewrite(it) })
                is Expression.Not -> Expression.Not(rewrite(e.expression))
                is Expression.ApplyUnaryComparison -> e
                is Expression.Exists -> Expression.Exists(
                    e.in_collection,
                    rewrite(e.predicate)
                )
                is Expression.ApplyBinaryComparison -> {
                    val col = e.column
                    if (col is ComparisonTarget.Column && col.path.isNotEmpty()) {
                        val baseComp = Expression.ApplyBinaryComparison(
                            operator = e.operator,
                            column = ComparisonTarget.Column(
                                name = col.name,
                                path = emptyList(),
                                field_path = col.field_path
                            ),
                            value = e.value
                        )
                        col.path.asReversed().fold(baseComp as Expression) { acc, pe ->
                            val pePred = rewrite(pe.predicate)
                            val combined = if (pePred is Expression.And && pePred.expressions.isEmpty()) acc else Expression.And(listOf(pePred, acc))
                            Expression.Exists(
                                in_collection = ExistsInCollection.Related(
                                    relationship = pe.relationship,
                                    arguments = pe.arguments
                                ),
                                predicate = combined
                            )
                        }
                    } else {
                        e
                    }
                }
            }
        }
        return expression?.let { rewrite(it) }
    }

    override fun buildComparison(
        col: Field<Any>,
        operator: ApplyBinaryComparisonOperator,
        listVal: List<Field<Any>>,
        columnType: NDCScalar?
    ): Condition {
        return when (operator) {
            ApplyBinaryComparisonOperator.REGEX -> {
                val v = listVal.firstOrNull() ?: return DSL.falseCondition()
                DSL.condition("regexp_like(?, ?)", col as Field<String>, v as Field<String>)
            }
            ApplyBinaryComparisonOperator.NOT_REGEX -> {
                val v = listVal.firstOrNull() ?: return DSL.falseCondition()
                DSL.not(DSL.condition("regexp_like(?, ?)", col as Field<String>, v as Field<String>))
            }
            ApplyBinaryComparisonOperator.IREGEX -> {
                val v = listVal.firstOrNull() ?: return DSL.falseCondition()
                DSL.condition("regexp_like(?, ?, 'i')", col as Field<String>, v as Field<String>)
            }
            ApplyBinaryComparisonOperator.NOT_IREGEX -> {
                val v = listVal.firstOrNull() ?: return DSL.falseCondition()
                DSL.not(DSL.condition("regexp_like(?, ?, 'i')", col as Field<String>, v as Field<String>))
            }
            else -> super.buildComparison(col, operator, listVal, columnType)
        }
    }

    private fun buildCTE(
        request: QueryRequest,
        relationship: Relationship?,
        relSource: String?
    ): CommonTableExpression<*> {
        val predRewrittenRequest = request.copy(
            query = request.query.copy(
                predicate = rewritePathComparisonsToExists(request.query.predicate)
            )
        )

        return DSL.name(genCTEName(request.collection)).`as`(
            DSL.select(DSL.asterisk())
                .from(
                    DSL.select(DSL.table(getCollectionAsjOOQName(request.collection)).asterisk(),
                        DSL.rowNumber().over(
                            DSL.partitionBy(
                                mkJoinKeyFields(
                                    relationship, DSL.name(request.collection.split("."))
                                )
                            ).orderBy(
                                run {
                                    val orderByFields = translateIROrderByField(request) +
                                            if (request.isVariablesRequest()) listOf(
                                                DSL.field(
                                                    DSL.name(
                                                        listOf(
                                                            VARS,
                                                            INDEX
                                                        )
                                                    )
                                                )
                                            ) else emptyList()
                                    orderByFields.distinct().ifEmpty { listOf(DSL.trueCondition()) }
                                }
                            )
                        ).`as`(getRNName(request.collection))

                    )
                        .apply {
                            if (request.isVariablesRequest())
                                this.select(DSL.table(DSL.name(VARS)).asterisk())
                        }
                        .apply {
                            if (relationship != null
                                && (relationship.column_mapping.isNotEmpty() || relationship.arguments.isNotEmpty())
                            ) {
                                from(DSL.name(genCTEName(relSource ?: request.collection)))
                                    .innerJoin(DSL.name(relationship.target_collection.split(".")))
                                    .on(
                                        mkSQLJoin(
                                            relationship,
                                            sourceCollection = genCTEName(relSource ?: request.collection),
                                        )
                                    )
                            } else from(getCollectionAsjOOQName(request.collection))
                        }
                        .apply {
                            addJoinsRequiredForOrderByFields(
                                this as SelectJoinStep<*>,
                                request,
                                sourceCollection = request.collection
                            )
                        }
                        .apply {// cross join "vars" if this request contains variables
                            if (request.isVariablesRequest())
                                (this as SelectJoinStep<*>).crossJoin(DSL.name(VARS))
                        }
                        .apply {
                            addJoinsRequiredForPredicate(
                                predRewrittenRequest,
                                this as SelectJoinStep<*>
                            )
                        }
                        .where(getWhereConditions(predRewrittenRequest))
                        .asTable(request.collection.split(".").joinToString("_"))
                ).where(mkOffsetLimit(request, DSL.field(DSL.name(getRNName(request.collection)))))
        )
    }

    private fun <T> forEachQueryLevelRecursively(
        request: QueryRequest,
        elementFn: (request: QueryRequest, rel: Relationship?, relSource: String?) -> T
    ): List<T> {

        fun recur(
            request: QueryRequest,
            relationship: Relationship?,
            relSource: String? = null
        ): List<T> = buildList {
            add(elementFn(request, relationship, relSource))

            getQueryRelationFields(request.query.fields ?: emptyMap()).flatMapTo(this) {
                val rel = request.collection_relationships[it.value.relationship]!!
                val args =
                    if (rel.arguments.isEmpty() && rel.column_mapping.isEmpty() && it.value.arguments.isNotEmpty()) {
                        it.value.arguments
                    } else rel.arguments

                recur(
                    request = request.copy(
                        collection = rel.target_collection,
                        query = it.value.query
                    ),
                    relationship = rel.copy(arguments = args),
                    request.collection
                )
            }
        }

        return recur(request, null)
    }


    private fun genCTEName(collection: String) = "${collection}_CTE".split(".").joinToString("_")
    private fun getRNName(collection: String) = "${collection}_RN".split(".").joinToString("_")

    private fun buildRows(request: QueryRequest): Field<*> {
        val isObjectTarget = isTargetOfObjRel(request)
        val agg = if (isObjectTarget) DSL::jsonArrayAggDistinct else DSL::jsonArrayAgg
        return DSL.coalesce(
            agg(buildRow(request))
                .orderBy(
                    setOrderBy(request, isObjectTarget)
                ),
            DSL.jsonArray()
        )
    }

    private fun buildVariableRows(request: QueryRequest): Field<*> {
        return DSL.arrayAgg(buildRow(request))
            .over(DSL.partitionBy(DSL.field(DSL.name(listOf(genCTEName(request.collection), INDEX)))))
    }

    private fun buildRow(request: QueryRequest): Field<*> {
        return DSL.jsonObject(
            (request.query.fields?.map { (alias, field) ->
                when (field) {
                    is IRField.ColumnField -> {
                        val columnField = DSL.field(DSL.name(listOf(genCTEName(request.collection)) + field.column))
                        val (columnType, ndcScalar) = columnTypeTojOOQType(
                            SnowflakeJDBCSchemaGenerator::mapScalarType,
                            request.collection,
                            field
                        )
                        val castedField = castToSQLDataType(SNOWFLAKE, columnField, ndcScalar)
                        DSL.jsonEntry(
                            alias,
                            castedField
                        )
                    }

                    is IRField.RelationshipField -> {
                        val relation = request.collection_relationships[field.relationship]!!

                        DSL.jsonEntry(
                            alias,
                            DSL.coalesce(
                                DSL.field(
                                    DSL.name(
                                        createAlias(
                                            relation.target_collection,
                                            isAggOnlyRelationField(field)
                                        ),
                                        ROWS_AND_AGGREGATES
                                    )
                                ) as Field<*>,
                                setRelFieldDefaults(field)
                            )
                        )
                    }
                }
            } ?: emptyList<JSONEntry<*>>())
        )
    }

    private fun isTargetOfObjRel(request: QueryRequest): Boolean {
        return request.collection_relationships.values.find {
            it.target_collection == request.collection && it.relationship_type == RelationshipType.Object
        } != null
    }

    private fun setRelFieldDefaults(field: IRField.RelationshipField): Field<*> {
        return if (isAggOnlyRelationField(field))
            DSL.jsonObject("aggregates", setAggregateDefaults(field))
        else if (isAggRelationField(field))
            DSL.jsonObject(
                DSL.jsonEntry("rows", DSL.jsonArray()),
                DSL.jsonEntry("aggregates", setAggregateDefaults(field))
            )
        else DSL.jsonObject("rows", DSL.jsonArray())
    }


    private fun isAggRelationField(field: IRField.RelationshipField) = !field.query.aggregates.isNullOrEmpty()

    private fun isAggOnlyRelationField(field: IRField.RelationshipField) =
        field.query.fields == null && isAggRelationField(field)

    private fun setAggregateDefaults(field: IRField.RelationshipField): Field<*> =
        getDefaultAggregateJsonEntries(field.query.aggregates)

    private fun setOrderBy(request: QueryRequest, isObjectTarget: Boolean): List<Field<*>> {
        return if (isObjectTarget /* || request.isNativeQuery() */) emptyList()
        else listOf(DSL.field(DSL.name(getRNName(request.collection))) as Field<*>)
    }

    private fun buildSelections(request: QueryRequest): Select<*> {
        val selects = forEachQueryLevelRecursively(request, CTEQueryGenerator::buildSelect)

        // this is a non-relational query so just return the single select
        if (selects.size == 1) return selects.first().third

        selects.forEachIndexed { idx, (currentRequest, relSource, currentSelect) ->
            val relationships = getQueryRelationFields(currentRequest.query.fields).values.map {
                val rel = currentRequest.collection_relationships[it.relationship]!!
                val args = if (rel.arguments.isEmpty() && rel.column_mapping.isEmpty() && it.arguments.isNotEmpty()) {
                    it.arguments
                } else rel.arguments
                rel.copy(arguments = args)
            }

            val distinctRelationships = relationships.distinctBy { it.target_collection }
            distinctRelationships.forEach { relationship ->
                // Find the nearest matching child select that appears AFTER the current index
                var chosenIndex: Int? = null
                var chosenTriple: Triple<QueryRequest, String?, SelectJoinStep<*>>? = null
                var j = idx + 1
                while (j < selects.size) {
                    val cand = selects[j]
                    if (cand.first.collection == relationship.target_collection && cand.second == currentRequest.collection) {
                        chosenIndex = j
                        chosenTriple = cand
                        break
                    }
                    j++
                }

                if (chosenIndex != null && chosenTriple != null) {
                    val (innerRequest, _, innerSelect) = chosenTriple!!
                    // Use the canonical alias expected by buildRow() references
                    val innerAlias = createAlias(
                        innerRequest.collection,
                        isAggregateOnlyRequest(innerRequest)
                    )

                    currentSelect
                        .leftJoin(
                            innerSelect.asTable(innerAlias)
                        )
                        .on(
                            mkSQLJoin(
                                relationship,
                                sourceCollection = genCTEName(currentRequest.collection),
                                targetTableNameTransform = { innerAlias }
                            )
                        )
                }
            }
        }
        return selects.first().third
    }

    private fun buildSelect(
        request: QueryRequest,
        relationship: Relationship? = null,
        relSource: String? = null
    ): Triple<QueryRequest, String?, SelectJoinStep<*>> {
        val joinFields = if (relationship != null)
            mkJoinKeyFields(relationship, genCTEName(relationship.target_collection))
        else emptyList()

        return Triple(
            request,
            relSource,
            run {
                val rowsBuilder: (QueryRequest) -> Field<*> =
                    if (request.isVariablesRequest()) {
                        val idxField: Field<*> = DSL.field(DSL.name(listOf(genCTEName(request.collection), INDEX)))
                        val partitionFields: List<Field<*>> = joinFields.map { it as Field<*> } + idxField
                        { _: QueryRequest ->
                            DSL.arrayAgg(buildRow(request))
                                .over(DSL.partitionBy(*partitionFields.toTypedArray()))
                        }
                    } else {
                        CTEQueryGenerator::buildRows
                    }

                DSL.selectDistinct(
                    listOf(
                        buildOuterStructure(
                            request,
                            rowsBuilder
                        ).`as`(ROWS_AND_AGGREGATES)
                    ) + if (request.isVariablesRequest())
                        listOf(DSL.field(DSL.name(listOf(genCTEName(request.collection), INDEX))))
                    else emptyList()
                )
                    .apply {
                        this.select(joinFields)
                    }
                    .from(DSL.name(genCTEName(request.collection)))
                    .apply {
                        if (joinFields.isNotEmpty() && !request.isVariablesRequest()) groupBy(joinFields)
                    }
            }
        )
    }

    private fun createAlias(collection: String, isAggregateOnly: Boolean): String {
        return "$collection${if (isAggregateOnly) "_AGG" else ""}".replace(".", "_")
    }
}
