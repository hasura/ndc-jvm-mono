package io.hasura.snowflake

import io.hasura.ndc.ir.*
import io.hasura.ndc.ir.Field as IRField
import io.hasura.ndc.ir.extensions.isVariablesRequest
import io.hasura.ndc.sqlgen.BaseQueryGenerator
import io.hasura.ndc.sqlgen.BaseQueryGenerator.Companion.INDEX
import io.hasura.ndc.sqlgen.BaseQueryGenerator.Companion.ROWS_AND_AGGREGATES
import io.hasura.ndc.sqlgen.DatabaseType.SNOWFLAKE
import io.hasura.snowflake.SnowflakeJDBCSchemaGenerator
import io.hasura.ndc.common.NDCScalar

import org.jooq.*
import org.jooq.impl.DSL

/**
 * SnowflakePlanBuilder
 *
 * Recursive, bottom-up CTE planner for Snowflake that follows the canonical shape:
 *  - VarsCTE (foreach)
 *  - Per-node BaseCTE with RN (and pagination)
 *  - Optional PickCTE (RN=1) for object targets
 *  - For 1:N children: ChildAggCTE grouped by parent keys (and idx)
 *  - AssemblyCTE that LEFT JOINs pre-aggregated children and builds JSON
 *  - Final SELECT: array_agg ordered either by RN (single) or idx (foreach)
 */
object SnowflakePlanBuilder : BaseQueryGenerator() {

    data class PlanNode(
        val request: QueryRequest,
        val relationshipFromParent: Relationship?,
        val aliasFromParent: String?,
        val pathSuffix: String,
        val base: CommonTableExpression<*>,
        val pick: CommonTableExpression<*>?,
        val childAggs: List<CommonTableExpression<*>>,
        val asm: CommonTableExpression<*>,
        val children: List<PlanNode>
    )

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


    override fun queryRequestToSQL(request: QueryRequest): Select<*> = buildFinal(request)

    override fun forEachQueryRequestToSQL(request: QueryRequest): Select<*> = buildFinal(request)

    private fun buildFinal(request: QueryRequest): Select<*> {
        val varsCTE = if (request.isVariablesRequest()) listOf(buildVarsCTE(request)) else emptyList()
        val plan = buildPlanRec(request, null, null, null, "", null)

        val allCTEs = collectCTEs(plan)
        val withStep = mkNativeQueryCTEs(request)
            .with(varsCTE)
            .with(allCTEs)

        val asmAlias = cteName(request.collection, "") + "_ASM"
        val dataTable = DSL.table(DSL.name(asmAlias))

        return if (request.isVariablesRequest()) {
            withStep
                .select(
                    DSL.jsonArrayAgg(

                        DSL.coalesce(
                            DSL.field(DSL.name("data", ROWS_AND_AGGREGATES)) as Field<*>,
                            DSL.jsonObject("rows", DSL.jsonArray())
                        )
                    ).orderBy(DSL.field(DSL.name(VARS, INDEX)))
                )
                .from(DSL.name(VARS))
                .leftJoin(dataTable.asTable("data"))
                .on(DSL.field(DSL.name("data", INDEX)).eq(DSL.field(DSL.name(VARS, INDEX))))
        } else {
            withStep
                .select(DSL.jsonArrayAgg(DSL.field(DSL.name("data", ROWS_AND_AGGREGATES))))
                .from(dataTable.asTable("data"))
        }
    }


    private fun collectCTEs(plan: PlanNode): List<CommonTableExpression<*>> {
        val list = mutableListOf<CommonTableExpression<*>>()
        // 1) Base for this node
        list.add(plan.base)
        // 2) Children CTEs (their base/agg/asm) must come before parent childAggs that depend on child ASM
        plan.children.forEach { child -> list.addAll(collectCTEs(child)) }
        // 3) Parent child aggregate CTEs depend on child ASM; add them now
        plan.childAggs.forEach { list.add(it) }
        // 4) Optional PICK for object targets (depends only on base)
        plan.pick?.let { list.add(it) }
        // 5) Assembly for this node (depends on child aggs and possibly pick)
        list.add(plan.asm)
        return list
    }

    private fun buildPlanRec(
        request: QueryRequest,
        relationship: Relationship?,
        relSource: String?,
        aliasFromParent: String?,
        pathSuffix: String,
        parentPathSuffix: String?
    ): PlanNode {
        val base = buildBaseCTE(request, relationship, relSource, pathSuffix, parentPathSuffix)
        val isObjTarget = relationship?.relationship_type == RelationshipType.Object
        val pick = buildPickCTE(base, request, isObjTarget, pathSuffix)
        val relFields = getQueryRelationFields(request.query.fields)
        val children = relFields.map { (alias, field) ->
            val baseRel = request.collection_relationships[field.relationship]!!
            val args = if (baseRel.arguments.isEmpty() && baseRel.column_mapping.isEmpty() && field.arguments.isNotEmpty()) field.arguments else baseRel.arguments
            val childReq = request.copy(collection = baseRel.target_collection, query = field.query, arguments = emptyMap())
            val childSuffix = if (pathSuffix.isEmpty()) alias else pathSuffix + "__" + alias
            buildPlanRec(childReq, baseRel.copy(arguments = args), request.collection, alias, childSuffix, pathSuffix)
        }
        val asm = buildAssemblyCTE(request, (pick ?: base), children, relationship, pathSuffix)
        val childAggRows = children
            .map { buildChildAggCTE(it, request) }
        val childAggMetrics = children
            .filter { getAggregateFields(it.request).isNotEmpty() }
            .map { buildChildAggMetricsCTE(it, request) }
        val childAggs = childAggRows + childAggMetrics
        return PlanNode(request, relationship, aliasFromParent, pathSuffix, base, pick, childAggs, asm, children)
    }

    // --- Helpers ---

    private fun cteName(collection: String, suffix: String = ""): String {
        val base = (collection + "_CTE").split(".").joinToString("_")
        return if (suffix.isEmpty()) base else base + "_" + suffix.replace('.', '_')
    }
    private fun rnName(collection: String, suffix: String = ""): String {
        val base = (collection + "_RN").split(".").joinToString("_")
        return if (suffix.isEmpty()) base else base + "_" + suffix.replace('.', '_')
    }

    private fun tojOOQName(collection: String): Name = DSL.name(collection.split("."))

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

    private fun buildBaseCTE(
        request: QueryRequest,
        relationship: Relationship?,
        relSource: String?,
        pathSuffix: String,
        parentPathSuffix: String?
    ): CommonTableExpression<*> {
        val predRewrittenRequest = request.copy(
            query = request.query.copy(
                predicate = rewritePathComparisonsToExists(request.query.predicate)
            )
        )

        val rnAlias = rnName(request.collection, pathSuffix)
        val cteAlias = cteName(request.collection, pathSuffix)

        val baseInner = DSL
            .select(DSL.table(tojOOQName(request.collection)).asterisk())
            .select(
                DSL.rowNumber().over(
                    DSL.partitionBy(
                        mkJoinKeyFields(
                            relationship,
                            tojOOQName(request.collection)
                        )
                    ).orderBy(
                        run {
                            val orderByFields = translateIROrderByField(request) +
                                    if (request.isVariablesRequest() && request.root_collection == request.collection) listOf(
                                        DSL.field(DSL.name(listOf(VARS, INDEX)))
                                    ) else emptyList()
                            orderByFields.distinct().ifEmpty { listOf(DSL.trueCondition()) }
                        }
                    )
                ).`as`(rnAlias)
            )
            .apply {
                if (request.isVariablesRequest()) {
                    if (relationship == null) {
                        this.select(DSL.table(DSL.name(VARS)).asterisk())
                    } else {
                        this.select(
                            DSL.field(
                                DSL.name(listOf(cteName(relSource ?: request.collection, parentPathSuffix ?: ""), INDEX))
                            ).`as`(DSL.name(INDEX))
                        )
                    }
                }
            }

        val baseFrom = baseInner
            .apply {
                if (relationship != null && (relationship.column_mapping.isNotEmpty() || relationship.arguments.isNotEmpty())) {
                    from(DSL.name(cteName(relSource ?: request.collection, parentPathSuffix ?: "")))
                        .innerJoin(DSL.name(relationship.target_collection.split(".")))
                        .on(
                            mkSQLJoin(
                                relationship,
                                sourceCollection = cteName(relSource ?: request.collection, parentPathSuffix ?: "")
                            )
                        )
                } else from(tojOOQName(request.collection))
            }
            .apply {
                addJoinsRequiredForOrderByFields(this as SelectJoinStep<*>, request, sourceCollection = request.collection)
            }
            .apply {
                if (request.isVariablesRequest() && relationship == null)
                    (this as SelectJoinStep<*>).crossJoin(DSL.name(VARS))
            }
            .apply {
                addJoinsRequiredForPredicate(predRewrittenRequest, this as SelectJoinStep<*>)
            }
            .where(getWhereConditions(predRewrittenRequest))
            .asTable(request.collection.split(".").joinToString("_"))

        val outer = DSL
            .select(DSL.asterisk())
            .from(baseFrom)
            .where(
                mkOffsetLimit(request, DSL.field(DSL.name(rnAlias))).and(
                    if (relationship?.relationship_type == RelationshipType.Object)
                        DSL.field(DSL.name(rnAlias)).eq(DSL.inline(1)) else DSL.noCondition()
                )
            )

        return DSL.name(cteAlias).`as`(outer)
    }

    private fun buildPickCTE(
        base: CommonTableExpression<*>,
        request: QueryRequest,
        isObjectTarget: Boolean,
        pathSuffix: String
    ): CommonTableExpression<*>? {
        if (!isObjectTarget) return null
        val rnAlias = rnName(request.collection, pathSuffix)
        val pickAlias = cteName(request.collection, pathSuffix) + "_PICK"
        val picked = DSL.select(DSL.asterisk())
            .from(base)
            .where(DSL.field(DSL.name(rnAlias)).eq(DSL.inline(1)))
        return DSL.name(pickAlias).`as`(picked)
    }

    private fun buildChildAggCTE(child: PlanNode, parent: QueryRequest): CommonTableExpression<*> {
        val childAsmAlias = cteName(child.request.collection, child.pathSuffix) + "_ASM"
        val aggAlias = cteName(parent.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG"

        val rel = child.relationshipFromParent!!
        // Group by the child ASM’s projected FK columns that map back to the parent’s keys
        val parentJoinCols: List<Field<*>> = when {
            rel.column_mapping.isNotEmpty() -> rel.column_mapping.values.map { DSL.field(DSL.name(it)) as Field<*> }
            rel.arguments.isNotEmpty() -> rel.arguments.keys.map { DSL.field(DSL.name(it)) as Field<*> }
            else -> emptyList()
        }
        val idxField = if (parent.isVariablesRequest()) listOf(DSL.field(DSL.name(INDEX)) as Field<*>) else emptyList()
        val groupCols: List<Field<*>> = parentJoinCols + idxField

        val select = DSL
            .select(*(groupCols.toTypedArray()))
            .select(
                DSL.coalesce(
                    DSL.jsonArrayAgg(DSL.field(DSL.name(childAsmAlias, ROWS_AND_AGGREGATES)))
                        .orderBy(DSL.field(DSL.name(childAsmAlias, rnName(child.request.collection, child.pathSuffix)), Any::class.java).asc()) ,
                    DSL.jsonArray()
                ).`as`(DSL.name(ROWS_AND_AGGREGATES))
            )
            .from(DSL.name(childAsmAlias))
            .groupBy(*(groupCols.toTypedArray()))

        return DSL.name(aggAlias).`as`(select)
    }

    private fun buildChildAggMetricsCTE(child: PlanNode, parent: QueryRequest): CommonTableExpression<*> {
        val childBaseAlias = cteName(child.request.collection, child.pathSuffix)
        val aggMetricsAlias = cteName(parent.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG_METRICS"
        val rel = child.relationshipFromParent!!

        val parentJoinCols: List<Field<*>> = when {
            rel.column_mapping.isNotEmpty() -> rel.column_mapping.values.map { DSL.field(DSL.name(it)) as Field<*> }
            rel.arguments.isNotEmpty() -> rel.arguments.keys.map { DSL.field(DSL.name(it)) as Field<*> }
            else -> emptyList()
        }
        val idxField = if (parent.isVariablesRequest()) listOf(DSL.field(DSL.name(INDEX)) as Field<*>) else emptyList()
        val groupCols: List<Field<*>> = parentJoinCols + idxField

        val aggregatesJson: Field<*> = buildAggregates(child.request).`as`(DSL.name("aggregates"))

        val select = DSL
            .select(*(groupCols.toTypedArray()))
            .select(aggregatesJson)
            .from(DSL.name(childBaseAlias))
            .groupBy(*(groupCols.toTypedArray()))

        return DSL.name(aggMetricsAlias).`as`(select)
    }

    private fun buildAssemblyCTE(
        request: QueryRequest,
        baseOrPick: CommonTableExpression<*>,
        children: List<PlanNode>,
        relationshipFromParent: Relationship?,
        pathSuffix: String
    ): CommonTableExpression<*> {
        val asmAlias = cteName(request.collection, pathSuffix) + "_ASM"

        val withChildren = DSL.select().from(baseOrPick).let { sel ->
            var cur = sel as SelectJoinStep<*>
            children.forEach { child ->
                // Child aggregate CTE alias is deterministic; it was created in buildPlanRec
                val aggAlias = cteName(request.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG"
                val joinCond = mkSQLJoin(
                    child.relationshipFromParent!!,
                    sourceCollection = cteName(request.collection, pathSuffix),
                    targetTableNameTransform = { aggAlias }
                ).let { cond ->
                    if (request.isVariablesRequest()) cond.and(
                        DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX))
                            .eq(DSL.field(DSL.name(aggAlias, INDEX)))
                    ) else cond
                }
        // When no relationship from parent, group by the parent’s own key; avoid referencing child table names
        fun parentGroupKeys(): List<Field<*>> {
            return if (relationshipFromParent == null) emptyList() else mkJoinKeyFields(
                relationshipFromParent,
                tojOOQName(request.collection)
            ).map { it as Field<*> }
        }

                cur = cur.leftJoin(DSL.name(aggAlias)).on(joinCond)

                // Also join aggregate metrics only when the child has aggregates
                if (getAggregateFields(child.request).isNotEmpty()) {
                    val aggMetricsAlias = cteName(request.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG_METRICS"
                    val metricsJoinCond = mkSQLJoin(
                        child.relationshipFromParent!!,
                        sourceCollection = cteName(request.collection, pathSuffix),
                        targetTableNameTransform = { aggMetricsAlias }
                    ).let { cond ->
                        if (request.isVariablesRequest()) cond.and(
                            DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX))
                                .eq(DSL.field(DSL.name(aggMetricsAlias, INDEX)))
                        ) else cond
                    }
                    cur = cur.leftJoin(DSL.name(aggMetricsAlias)).on(metricsJoinCond)
                }
            }
            cur
        }

        val rowJson = DSL.jsonObject(
            (request.query.fields?.map { (alias, field) ->
                when (field) {
                    is IRField.ColumnField -> {
                        val columnField = DSL.field(DSL.name(listOf(cteName(request.collection, pathSuffix)) + field.column))
                        val (_, ndcScalar) = columnTypeTojOOQType(
                            SnowflakeJDBCSchemaGenerator::mapScalarType,
                            request.collection,
                            field
                        )
                        val casted = castToSQLDataType(SNOWFLAKE, columnField, ndcScalar) as Field<Any>
                        DSL.jsonEntry(alias, casted)
                    }
                    is IRField.RelationshipField -> {
                        val rel = request.collection_relationships[field.relationship]!!
                        val childSuffix = if (pathSuffix.isEmpty()) alias else pathSuffix + "__" + alias
                        val childAggAlias = cteName(request.collection, childSuffix) + "_" + cteName(rel.target_collection, childSuffix) + "_AGG"
                        val childAggMetricsAlias = cteName(request.collection, childSuffix) + "_" + cteName(rel.target_collection, childSuffix) + "_AGG_METRICS"
                        val hasAggs = !(field.query.aggregates.isNullOrEmpty())
                        val hasRowFields = !(field.query.fields.isNullOrEmpty())

                        val entries = buildList<JSONEntry<*>> {
                            if (hasRowFields) {
                                add(
                                    DSL.jsonEntry(
                                        "rows",
                                        DSL.coalesce(
                                            DSL.field(DSL.name(childAggAlias, ROWS_AND_AGGREGATES)) as Field<*>,
                                            DSL.jsonArray()
                                        )
                                    )
                                )
                            }
                            if (hasAggs) {
                                add(
                                    DSL.jsonEntry(
                                        "aggregates",
                                        DSL.coalesce(
                                            DSL.field(DSL.name(childAggMetricsAlias, "aggregates")) as Field<*>,
                                            this@SnowflakePlanBuilder.getDefaultAggregateJsonEntries(field.query.aggregates)
                                        )
                                    )
                                )
                            }
                        }

                        DSL.jsonEntry(
                            alias,
                            DSL.jsonObject(entries)
                        )
                    }
                }
            } ?: emptyList<JSONEntry<*>>())
        )

        if (relationshipFromParent == null) {
            val rowsField = DSL.coalesce(
                DSL.jsonArrayAgg(rowJson).orderBy(listOf(DSL.field(DSL.name(rnName(request.collection, pathSuffix))) as Field<*>)),
                DSL.jsonArray()
            )
            val outerJson = buildOuterStructure(request, buildRows = { _: QueryRequest -> rowsField })

            val idxSelectRoot: List<SelectField<*>> = if (request.isVariablesRequest())
                listOf(DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX)).`as`(DSL.name(INDEX))) else emptyList()

            var selectJoin: SelectJoinStep<*> = DSL
                .select(outerJson.`as`(ROWS_AND_AGGREGATES))
                .select(idxSelectRoot.map { it as Field<*> })
                .from(baseOrPick) as SelectJoinStep<*>

            children.forEach { child ->
                val aggAlias = cteName(request.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG"
                val joinCond = mkSQLJoin(
                    child.relationshipFromParent!!,
                    sourceCollection = cteName(request.collection, pathSuffix),
                    targetTableNameTransform = { aggAlias }
                ).let { cond ->
                    if (request.isVariablesRequest()) cond.and(
                        DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX))
                            .eq(DSL.field(DSL.name(aggAlias, INDEX)))
                    ) else cond
                }
                selectJoin = selectJoin.leftJoin(DSL.name(aggAlias)).on(joinCond)

                // Also join aggregate metrics only when the child has aggregates
                if (getAggregateFields(child.request).isNotEmpty()) {
                    val aggMetricsAlias = cteName(request.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG_METRICS"
                    val metricsJoinCond = mkSQLJoin(
                        child.relationshipFromParent!!,
                        sourceCollection = cteName(request.collection, pathSuffix),
                        targetTableNameTransform = { aggMetricsAlias }
                    ).let { cond ->
                        if (request.isVariablesRequest()) cond.and(
                            DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX))
                                .eq(DSL.field(DSL.name(aggMetricsAlias, INDEX)))
                        ) else cond
                    }
                    selectJoin = selectJoin.leftJoin(DSL.name(aggMetricsAlias)).on(metricsJoinCond)
                }
            }

            val finalSelect = if (request.isVariablesRequest())
                (selectJoin as SelectGroupByStep<*>)
                    .groupBy(DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX)))
            else selectJoin

            return DSL.name(asmAlias).`as`(finalSelect)
        } else {
            val rel = relationshipFromParent
            // Project join key fields from the child base CTE and alias them to simple names
            val joinKeyFields: List<SelectField<*>> = when {
                rel!!.column_mapping.isNotEmpty() -> rel.column_mapping.values.map { key ->
                    DSL.field(DSL.name(listOf(cteName(request.collection, pathSuffix), key))).`as`(DSL.name(key))
                }
                rel.arguments.isNotEmpty() -> rel.arguments.keys.map { arg ->
                    DSL.field(DSL.name(arg)).`as`(DSL.name(arg))
                }
                else -> emptyList()
            }
            val idxSelect: List<SelectField<*>> = if (request.isVariablesRequest())
                listOf(DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX)).`as`(DSL.name(INDEX))) else emptyList()
            val rnField = DSL.field(DSL.name(rnName(request.collection, pathSuffix))).`as`(DSL.name(rnName(request.collection, pathSuffix)))

            val inner = DSL
                .select(DSL.asterisk())
                .from(baseOrPick)
                .asTable(cteName(request.collection, pathSuffix))

            var selectJoin: SelectJoinStep<*> = DSL
                .select(rowJson.`as`(ROWS_AND_AGGREGATES))
                .select(joinKeyFields.map { it as Field<*> })
                .select(idxSelect.map { it as Field<*> })
                .select(DSL.field(DSL.name(rnName(request.collection, pathSuffix))) as Field<*>)
                .from(inner) as SelectJoinStep<*>

            children.forEach { child ->
                val aggAlias = cteName(request.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG"
                val joinCond = mkSQLJoin(
                    child.relationshipFromParent!!,
                    sourceCollection = cteName(request.collection, pathSuffix),
                    targetTableNameTransform = { aggAlias }
                ).let { cond ->
                    if (request.isVariablesRequest()) cond.and(
                        DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX))
                            .eq(DSL.field(DSL.name(aggAlias, INDEX)))
                    ) else cond
                }
                selectJoin = selectJoin.leftJoin(DSL.name(aggAlias)).on(joinCond)

                // Also join aggregate metrics only when the child has aggregates
                if (getAggregateFields(child.request).isNotEmpty()) {
                    val aggMetricsAlias = cteName(request.collection, child.pathSuffix) + "_" + cteName(child.request.collection, child.pathSuffix) + "_AGG_METRICS"
                    val metricsJoinCond = mkSQLJoin(
                        child.relationshipFromParent!!,
                        sourceCollection = cteName(request.collection, pathSuffix),
                        targetTableNameTransform = { aggMetricsAlias }
                    ).let { cond ->
                        if (request.isVariablesRequest()) cond.and(
                            DSL.field(DSL.name(cteName(request.collection, pathSuffix), INDEX))
                                .eq(DSL.field(DSL.name(aggMetricsAlias, INDEX)))
                        ) else cond
                    }
                    selectJoin = selectJoin.leftJoin(DSL.name(aggMetricsAlias)).on(metricsJoinCond)
                }
            }

            return DSL.name(asmAlias).`as`(selectJoin)
        }
    }
}


    // TODO: Implement recursive traversal to build child agg CTEs and assembly CTEs

