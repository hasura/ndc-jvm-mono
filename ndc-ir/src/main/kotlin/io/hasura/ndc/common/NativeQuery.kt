package io.hasura.ndc.common

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper



import com.fasterxml.jackson.module.kotlin.treeToValue


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

class NativeQuerySqlDeserializer : JsonDeserializer<NativeQuerySql>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): NativeQuerySql {
        // Create a node tree from the parser
        val node = p.readValueAsTree<JsonNode>()

        // If the input is a plain string, create an Inline instance
        // This is done for backwards compatibility. When native queries were introduced,
        // it was accepted as a string.
        if (node.isTextual()) {
            return NativeQuerySql.Inline(node.asText())
        }

        // Otherwise, process the object manually
        if (node.isObject) {
            // Case 2: Object with parts array (existing format)
            if (node.has("parts")) {
                val partsNode = node.get("parts")
                if (partsNode.isArray()) {
                    val sqlBuilder = StringBuilder()

                    for (partNode in partsNode) {
                        if (partNode.isObject && partNode.has("type") && partNode.has("value")) {
                            val type = partNode.get("type").asText()
                            val value = partNode.get("value").asText()

                            when (type) {
                                "text" -> sqlBuilder.append(value)
                                "parameter" -> sqlBuilder.append("{{").append(value).append("}}")
                                else -> throw JsonMappingException.from(p, "Unknown part type: $type")
                            }
                        }
                    }

                    // Reconstruct SQL and create an Inline instance
                    return NativeQuerySql.Inline(sqlBuilder.toString())
                }
            }

            // Case 3: Type-based format
            val typeNode = node.get("type")
            if (typeNode != null && typeNode.isTextual) {
                val type = typeNode.asText()

                if (type == "inline") {
                    val sqlNode = node.get("sql")
                    if (sqlNode != null && sqlNode.isTextual) {
                        return NativeQuerySql.Inline(sqlNode.asText())
                    }
                } else if (type == "file") {
                    val filePathNode = node.get("filePath")
                    if (filePathNode != null && filePathNode.isTextual) {
                        return NativeQuerySql.FromFile(filePathNode.asText())
                    }
                }
            }
        }

        throw JsonMappingException.from(p, "Cannot deserialize NativeQuerySql")
    }
}

class NativeQuerySqlSerializer : JsonSerializer<NativeQuerySql>() {
    override fun serialize(
            value: NativeQuerySql,
            gen: JsonGenerator,
            serializers: SerializerProvider
    ) {
        when (value) {
            is NativeQuerySql.Inline -> {
                gen.writeStartObject()
                gen.writeStringField("type", "inline")
                gen.writeStringField("sql", value.sql)
                gen.writeEndObject()
            }
            is NativeQuerySql.FromFile -> {
                gen.writeStartObject()
                gen.writeStringField("type", "file")
                gen.writeStringField("filePath", value.filePath)
                gen.writeEndObject()
            }
        }
    }
}


@JsonDeserialize(using = NativeQuerySqlDeserializer::class)
@JsonSerialize(using = NativeQuerySqlSerializer::class)
sealed class NativeQuerySql {
    abstract fun getParts(configDir: String? = null): List<NativeQueryPart>

    // For backward compatibility - delegate to getParts()
    val parts: List<NativeQueryPart>
        get() = getParts()

    @JsonTypeName("inline")
    data class Inline @JsonCreator constructor(val sql: String) : NativeQuerySql() {
        override fun getParts(configDir: String?): List<NativeQueryPart> = parseSQL(sql)
    }

    @JsonTypeName("file")
    data class FromFile @JsonCreator constructor(val filePath: String) : NativeQuerySql() {
        override fun getParts(configDir: String?): List<NativeQueryPart> {
            if (configDir == null) {
                throw IllegalArgumentException("Configuration directory must be provided for file-based SQL")
            }

            val sqlContent = try {
                File(configDir, filePath).readText()
            } catch (e: Exception) {
                throw IllegalStateException("Failed to read SQL file at path: $filePath", e)
            }
            return parseSQL(sqlContent)
        }
    }

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromString(sql: String): NativeQuerySql = Inline(sql)

        @JvmStatic
        fun parseSQL(sql: String): List<NativeQueryPart> =
            sql.split("{{")
                .flatMap{
                    val parts = it.split("}}", limit=2)
                    when (parts.size) {
                        1 -> listOf(NativeQueryPart.Text(parts[0]))
                        else -> listOf(NativeQueryPart.Parameter(parts[0]), NativeQueryPart.Text(parts[1]))
                    }
                }
                .let{
                    if (it.isEmpty()) listOf(NativeQueryPart.Text(""))
                    else it.filterNot { part -> part.value.isEmpty() && part is NativeQueryPart.Text }
                }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = NativeQueryPart.Text::class, name = "text"),
    JsonSubTypes.Type(value = NativeQueryPart.Parameter::class, name = "parameter")
)
sealed class NativeQueryPart(open val value: String) {
    @JsonTypeName("text")
    data class Text(override val value: String) : NativeQueryPart(value)

    @JsonTypeName("parameter")
    data class Parameter(override val value: String) : NativeQueryPart(value)
}
