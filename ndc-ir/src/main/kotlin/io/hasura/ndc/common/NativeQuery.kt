package io.hasura.ndc.common

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.hasura.ndc.ir.ArgumentInfo
import io.hasura.ndc.ir.Type


data class NativeQueryInfo(
    val sql: NativeQuerySql,
    val columns: Map<String, Type>,
    val arguments: Map<String, ArgumentInfo> = emptyMap(),
    val description: String? = null,
    val isProcedure: Boolean = false
)

data class NativeQuerySql(
    val parts: List<NativeQueryPart>
){
    @JsonCreator
    constructor (sql:String): this(parseSQL(sql))

    companion object {
        @JvmStatic
        fun parseSQL(sql: String) =
            sql.split("{{")
                .flatMap{
                    val parts = it.split("}}", limit=2)
                    when(parts.size){
                        1 -> listOf(NativeQueryPart.Text(parts[0]))
                        2 -> listOf(NativeQueryPart.Parameter(parts[0])) +
                                if(parts[1].isNotEmpty())
                                    listOf(NativeQueryPart.Text(parts[1]))
                                else emptyList()
                        else -> emptyList()
                    }
                }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed class NativeQueryPart(open val value: String) {
    @JsonTypeName("text")
    data class Text(override val value: String) : NativeQueryPart(value)
    @JsonTypeName("parameter")
    data class Parameter(override val value: String) : NativeQueryPart(value)
}


