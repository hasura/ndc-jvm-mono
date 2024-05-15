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
    val insert_schema: List<CollectionInsertSchema>,
    val operations: List<MutationOperation>,
    val collection_relationships: Map<String, Relationship> = emptyMap(),
)

data class MutationResponse(
    val operation_results: List<MutationOperationResult> = emptyList()
)

typealias FieldName = String

data class MutationOperationResult(
    val affected_rows: Int,
    val returning: List<Map<FieldName, Any?>>? = null,
)

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


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface MutationOperation {

    @JsonTypeName("insert")
    data class InsertMutationOperation(
        val collection: String,
        val rows: List<Map<String, Any>>,
        val post_insert_check: Expression? = null,
        val returning_fields: Map<String, Field>? = null,
    ) : MutationOperation

    @JsonTypeName("update")
    data class UpdateMutationOperation(
        val collection: String,
        val updates: List<RowUpdate>,
        val where: Expression? = null,
        val post_update_check: Expression? = null,
        val returning_fields: Map<String, Field>? = null,
    ) : MutationOperation

    @JsonTypeName("delete")
    data class DeleteMutationOperation(
        val collection: String,
        val where: Expression? = null,
        val returning_fields: Map<String, Field>? = null,
    ) : MutationOperation

    @JsonTypeName("procedure")
    data class ProcedureMutationOperation(
        val name: String,
        val arguments: Map<String, Any>,
        val fields: Map<String, Field>? = null,
    ) : MutationOperation
}




data class ExplainResponse (
    val details: Map<String, String>
)
