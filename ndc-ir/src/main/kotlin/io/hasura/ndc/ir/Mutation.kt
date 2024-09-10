package io.hasura.ndc.ir

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface RowUpdate {

    @JsonTypeName("set")
    data class Set (
        val column: String,
        val value: Any
    ) : RowUpdate

    @JsonTypeName("custom_operator")
    data class CustomOperator (
        val column: String,
        val operator_name: String, /* UpdateColumnOperatorName */
        val value: Any
    ) : RowUpdate
}

data class MutationRequest(
    val operations: List<MutationOperation>,
    val collection_relationships: Map<String, Relationship> = emptyMap(),
)

data class MutationResponse(
    val operation_results: List<MutationOperationResult> = emptyList()
)

typealias FieldName = String

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface MutationOperationResult {

    @JsonTypeName("procedure")
    data class ProcedureMutationOperationResult(
        val result: Map<String, Any> = emptyMap(),
    ) : MutationOperationResult
}

data class CollectionInsertSchema(
    val fields: Map<String, InsertFieldSchema>,
    val collection: String
)



@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface InsertFieldSchema {

    @JsonTypeName("object_relation")
    data class ObjectRelationInsertSchema(
        val insertion_order: ObjectRelationInsertionOrder,
        val relationship: String
    ) : InsertFieldSchema

    @JsonTypeName("array_relation")
    data class ArrayRelationInsertSchema(
        val relationship: String,
    ) : InsertFieldSchema

    @JsonTypeName("column")
    data class ColumnInsertSchema(
        val column: String
    ) : InsertFieldSchema

}

enum class ObjectRelationInsertionOrder {
    @JsonProperty("before_parent")
    BEFORE_PARENT,

    @JsonProperty("after_parent")
    AFTER_PARENT
}


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface MutationOperation {

    @JsonTypeName("procedure")
    data class ProcedureMutationOperation(
        val name: String,
        val arguments: Map<String, Any>,
        val fields: NestedField? = null,
    ) : MutationOperation
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface NestedField {

    @JsonTypeName("object")
    data class NestedObject(
        val fields: Map<String, Field>
    ) : NestedField

    @JsonTypeName("array")
    data class NestedArray(
        val fields: NestedField
    ) : NestedField
}




data class ExplainResponse (
    val details: Map<String, String>
)
