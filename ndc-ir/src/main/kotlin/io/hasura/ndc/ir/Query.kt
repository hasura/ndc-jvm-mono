package io.hasura.ndc.ir

import com.fasterxml.jackson.annotation.*


// /////////////////////////////////////////////////////////////////////////
// QUERY
// /////////////////////////////////////////////////////////////////////////

// Used in the "foreach" clause

data class QueryRequest(
    val collection: String,
    val query: Query,
    val arguments: Map<String, Argument> = emptyMap(),
    val collection_relationships: Map<String, Relationship> = emptyMap(),
    val variables: List<Map<String, Any>>? = null,
    val root_collection: String = collection
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Argument {

    @JsonTypeName("variable")
    data class Variable(val name: String) : Argument

    @JsonTypeName("literal")
    data class Literal(val value: Any) : Argument

    @JsonTypeName("column")
    data class Column(val name: String) : Argument
}

data class Relationship(
    val column_mapping: Map<String, String>,
    val relationship_type: RelationshipType,
    val target_collection: String,
    val arguments: Map<String, Argument>
)

enum class RelationshipType {
    @JsonProperty("object")
    Object,

    @JsonProperty("array")
    Array,
}


data class Query(
    val aggregates: Map<String, Aggregate>? = null,
    val fields: Map<String, Field>? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val order_by: OrderBy? = null,
    val predicate: Expression? = null,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Aggregate {

    @JsonTypeName("star_count")
    object StarCount : Aggregate

    @JsonTypeName("column_count")
    data class ColumnCount(val column: String, val distinct: Boolean) : Aggregate

    @JsonTypeName("single_column")
    data class SingleColumn(val column: String, val function: SingleColumnAggregateFunction) : Aggregate
}

enum class SingleColumnAggregateFunction {
    @JsonProperty("avg")
    AVG,

    @JsonProperty("sum")
    SUM,

    @JsonProperty("min")
    MIN,

    @JsonProperty("max")
    MAX,

    @JsonProperty("stddev_pop")
    STDDEV_POP,

    @JsonProperty("stddev_samp")
    STDDEV_SAMP,

    @JsonProperty("var_pop")
    VAR_POP,

    @JsonProperty("var_samp")
    VAR_SAMP
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Field {

    @JsonTypeName("column")
    data class ColumnField(val column: String) : Field

    @JsonTypeName("relationship")
    data class RelationshipField(
        val query: Query,
        val relationship: String,
        val arguments: Map<String, Argument>
    ) : Field
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface ComparisonValue {

    @JsonTypeName("column")
    data class ColumnComp(val column: ComparisonTarget) : ComparisonValue

    @JsonTypeName("scalar")
    data class ScalarComp(val value: Any) : ComparisonValue

    @JsonTypeName("variable")
    data class VariableComp(val name: String) : ComparisonValue
}

enum class OrderDirection {
    @JsonProperty("asc")
    Asc,

    @JsonProperty("desc")
    Desc
}

data class OrderBy(
    val elements: List<OrderByElement>
)


data class OrderByElement(
    val target: OrderByTarget,
    val order_direction: OrderDirection
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface OrderByTarget {

    val path: List<PathElement>

    @JsonTypeName("star_count_aggregate")
    data class OrderByStarCountAggregate(override val path: List<PathElement>) : OrderByTarget

    @JsonTypeName("column")
    data class OrderByColumn(val name: String, override val path: List<PathElement>) : OrderByTarget

    @JsonTypeName("single_column_aggregate")
    data class OrderBySingleColumnAggregate(
        val column: String,
        val function: SingleColumnAggregateFunction,
        override val path: List<PathElement>
    ) : OrderByTarget
}

data class PathElement(
    val relationship: String,
    val arguments: Map<String, Argument>,
    val predicate: Expression
)

// /////////////////////////////////////////////////////////////////////////
// OPERATORS
// /////////////////////////////////////////////////////////////////////////
enum class ApplyBinaryComparisonOperator {
    @JsonProperty("_eq")
    EQ,

    @JsonProperty("_gt")
    GT,

    @JsonProperty("_lt")
    LT,

    @JsonProperty("_gte")
    GTE,

    @JsonProperty("_lte")
    LTE,

    @JsonProperty("_in")
    IN,

    @JsonProperty("_is_null")
    IS_NULL,

    @JsonProperty("_like")
    LIKE,

    @JsonProperty("_contains")
    CONTAINS,
}

enum class ApplyUnaryComparisonOperator {
    @JsonProperty("is_null")
    IS_NULL
}

// /////////////////////////////////////////////////////////////////////////
// EXPRESSIONS
// /////////////////////////////////////////////////////////////////////////

interface ExpressionOnColumn {
    val column: ComparisonTarget
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Expression {

    @JsonTypeName("and")
    data class And(val expressions: List<Expression>) : Expression

    @JsonTypeName("or")
    data class Or(val expressions: List<Expression>) : Expression

    @JsonTypeName("not")
    data class Not(val expression: Expression) : Expression

    @JsonTypeName("unary_comparison_operator")
    data class ApplyUnaryComparison(
        val operator: ApplyUnaryComparisonOperator,
        val column: ComparisonTarget
    ) : Expression

    @JsonTypeName("binary_comparison_operator")
    data class ApplyBinaryComparison(
        val operator: ApplyBinaryComparisonOperator,
        override val column: ComparisonTarget,
        val value: ComparisonValue
    ) : Expression, ExpressionOnColumn

    // Test if a row exists that matches the where subexpression in the specified table (in_table)
    @JsonTypeName("exists")
    data class Exists(
        val in_collection: ExistsInCollection,
        val predicate: Expression
    ) : Expression
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface ComparisonTarget {

    val name: String

    @JsonTypeName("column")
    data class Column(
        override val name: String,
        val path: List<PathElement>,
        val field_path: List<String>? = null
    ) : ComparisonTarget

    @JsonTypeName("root_collection_column")
    data class RootCollectionColumn(
        override val name: String,
        val field_path: List<String>? = null
    ) : ComparisonTarget
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface ExistsInCollection {

    @JsonTypeName("related")
    data class Related(
        val relationship: String,
        val arguments: Map<String, Argument> = emptyMap()
    ) : ExistsInCollection

    @JsonTypeName("unrelated")
    data class Unrelated(
        val collection: String,
        val arguments: Map<String, Argument> = emptyMap()
    ) : ExistsInCollection
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RowSet(
    val aggregates: Map<String, Any>? = null,
    val rows: List<Map<String, Any>>? = null
)
