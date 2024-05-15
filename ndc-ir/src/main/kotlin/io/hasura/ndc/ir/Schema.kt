package io.hasura.ndc.ir

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName


data class SchemaResponse (
    val scalar_types: Map<String, ScalarType>,
    val object_types: Map<String, ObjectType>,
    val collections: List<CollectionInfo>,
    val functions: List<FunctionInfo>,
    val procedures: List<ProcedureInfo>
)

data class ScalarType (
    val aggregate_functions: Map<String, AggregateFunctionDefinition>,
    val comparison_operators: Map<String, ComparisonOperatorDefinition>,
)

data class ObjectType (
    val description: String? = null,
    val fields: Map<String, ObjectField>,
)

data class ObjectField (
    val description: String? = null,
    val arguments: Map<String, ArgumentInfo>,
    val type: Type
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface ComparisonOperatorDefinition {
    @JsonTypeName("equal")
    object Equal : ComparisonOperatorDefinition
    @JsonTypeName("in")
    object In : ComparisonOperatorDefinition
    @JsonTypeName("custom")
    data class Custom(val argument_type: Type) : ComparisonOperatorDefinition
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Type {
    @JsonTypeName("named")
    data class Named(val name: String) : Type
    @JsonTypeName("nullable")
    data class Nullable(val underlying_type: Type) : Type
    @JsonTypeName("array")
    data class Array(val element_type: Type) : Type
}

data class ArgumentInfo (
    val description: String? = null,
    @JsonProperty("type")
    val argument_type: Type
)

data class ProcedureInfo (
    val name: String,
    val description: String? = null,
    val arguments: Map<String, ArgumentInfo>,
    val result_type: Type,
)

data class AggregateFunctionDefinition (
    val result_type: Type
)

data class UpdateOperatorDefinition (
    val argument_type: Type
)

data class CollectionInfo (
    val name: String,
    val description: String? = null,
    val arguments: Map<String, ArgumentInfo>,
    val type: String,
    val insertable_columns: List<String>? = null,
    val updatable_columns: List<String>? = null,
    val deletable: Boolean,
    val uniqueness_constraints: Map<String, UniquenessConstraint>,
    val foreign_keys: Map<String, ForeignKeyConstraint>
)

data class FunctionInfo (
    val name: String,
    val description: String? = null,
    val arguments: Map<String, ArgumentInfo>,
    val result_type: Type
)

data class UniquenessConstraint (
    val unique_columns: List<String>
)

data class ForeignKeyConstraint (
    val column_mapping: Map<String, String>,
    val foreign_collection: String
)
