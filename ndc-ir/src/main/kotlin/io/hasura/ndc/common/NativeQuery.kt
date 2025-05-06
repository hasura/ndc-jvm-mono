package io.hasura.ndc.common

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.hasura.ndc.ir.ArgumentInfo
import io.hasura.ndc.ir.Type
import java.io.File

data class NativeQueryInfo(
    val sql: NativeQuerySql,
    val columns: Map<String, Type>,
    val arguments: Map<String, ArgumentInfo> = emptyMap(),
    val description: String? = null,
    val isProcedure: Boolean = false
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = NativeQuerySql.Inline::class, name = "inline"),
    JsonSubTypes.Type(value = NativeQuerySql.FromFile::class, name = "file")
)
sealed class NativeQuerySql {
    abstract fun getParts(configDir: String? = null): List<NativeQueryPart>

    @JsonTypeName("inline")
    data class Inline(val sql: String) : NativeQuerySql() {
        override fun getParts(configDir: String?): List<NativeQueryPart> = parseSQL(sql)
    }

    @JsonTypeName("file")
    data class FromFile(val filePath: String) : NativeQuerySql() {
        override fun getParts(configDir: String?): List<NativeQueryPart> {
            val sqlContent = try {
                File("../../../../../configs/oracle", filePath).readText()
            } catch (e: Exception) {
                throw IllegalStateException("Failed to read SQL file at path: $filePath", e)
            }
            return parseSQL(sqlContent)
        }
    }

    companion object {
        @JvmStatic
        fun parseSQL(sql: String): List<NativeQueryPart> =
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
