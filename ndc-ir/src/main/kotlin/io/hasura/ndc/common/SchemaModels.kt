package io.hasura.ndc.common

import io.hasura.ndc.ir.ForeignKeyConstraint

data class TableSchemaRow(
    val tableName: String,
    val tableType: TableType,
    val description: String?,
    val columns: List<ColumnSchemaRow>,
    val pks: List<String>?,
    val fks: Map<String, ForeignKeyConstraint>?
)

data class ColumnSchemaRow(
    val name: String,
    val description: String?,
    val type: String,
    val numeric_scale: Int?,
    val nullable: Boolean,
    val auto_increment: Boolean,
    val is_primarykey: Boolean?
)

data class FunctionSchemaRow(
    val function_catalog: String,
    val function_schema: String,
    val function_name: String,
    // Format: (N NUMBER, M NUMBER)
    val argument_signature: String,
    // Format: TABLE (N NUMBER, M NUMBER)
    val data_type: String,
    val comment: String?
)

enum class TableType {
    TABLE,
    VIEW
}