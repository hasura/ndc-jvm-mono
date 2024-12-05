package io.hasura.ndc.common

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.annotation.JsonValue
import java.io.File
import java.nio.file.Path

@JsonDeserialize(using = JdbcUrlConfigDeserializer::class)
sealed class JdbcUrlConfig {
    data class Literal(@JsonValue val value: String) : JdbcUrlConfig()
    data class EnvVar(val variable: String) : JdbcUrlConfig()
}

class JdbcUrlConfigDeserializer : JsonDeserializer<JdbcUrlConfig>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): JdbcUrlConfig {
        val node: JsonNode = p.codec.readTree(p)
        return when {
            node.isTextual -> JdbcUrlConfig.Literal(node.asText())
            node.isObject && node.has("variable") -> JdbcUrlConfig.EnvVar(node.get("variable").asText())
            else -> throw IllegalArgumentException("Invalid JdbcUrlConfig format")
        }
    }
}

data class ConnectorConfiguration(
    val jdbcUrl: JdbcUrlConfig = JdbcUrlConfig.EnvVar("JDBC_URL"),
    val jdbcProperties: Map<String, Any> = emptyMap(),
    val schemas: List<String> = emptyList(),
    val tables: List<TableSchemaRow> = emptyList(),
    val functions: List<FunctionSchemaRow> = emptyList(),
    val nativeQueries: Map<String, NativeQueryInfo> = emptyMap()
) {

    object Loader {
        private val mapper = jacksonObjectMapper()

        private const val DEFAULT_CONFIG_DIRECTORY = "/etc/connector"
        private const val CONFIG_FILE_NAME = "configuration.json"

        val CONFIG_DIRECTORY: String = System.getenv("HASURA_CONFIGURATION_DIRECTORY") ?: DEFAULT_CONFIG_DIRECTORY
        val config: ConnectorConfiguration = loadConfigFile(getConfigFilePath())

        fun getConfigFilePath(): Path {
            return Path.of(CONFIG_DIRECTORY, CONFIG_FILE_NAME)
        }

        fun loadConfigFile(path: Path): ConnectorConfiguration {
            val file = File(path.toString())
            return if (!file.exists()) {
                ConnectorConfiguration()
            } else {
                mapper.readValue<ConnectorConfiguration>(file)
            }
        }
    }
}
