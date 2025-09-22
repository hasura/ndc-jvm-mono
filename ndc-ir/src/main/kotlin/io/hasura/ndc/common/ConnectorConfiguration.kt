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
import kotlin.system.exitProcess

@JsonDeserialize(using = JdbcUrlConfigDeserializer::class)
sealed class JdbcUrlConfig {
    data class Literal(@JsonValue val value: String) : JdbcUrlConfig()
    data class EnvVar(val variable: String) : JdbcUrlConfig()

    fun resolve(): String =
        when (this) {
            is Literal -> value
            is EnvVar -> System.getenv(variable)
                            ?: throw IllegalStateException(
                                    "Environment variable $variable not found",
                            )

        }

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

@JsonDeserialize(using = CacheConfigValueDeserializer::class)
sealed class CacheConfigValue {
    data class Literal(@JsonValue val value: Long) : CacheConfigValue()
    data class EnvVar(val variable: String) : CacheConfigValue()

    fun resolve(defaultValue: Long): Long =
        when (this) {
            is Literal -> value
            is EnvVar -> System.getenv(variable)?.toLongOrNull() ?: defaultValue
        }
}

class CacheConfigValueDeserializer : JsonDeserializer<CacheConfigValue>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): CacheConfigValue {
        val node: JsonNode = p.codec.readTree(p)
        return when {
            node.isNumber -> CacheConfigValue.Literal(node.asLong())
            node.isTextual -> {
                // Try to parse as number first, if it fails treat as env var
                node.asText().toLongOrNull()?.let { CacheConfigValue.Literal(it) }
                    ?: CacheConfigValue.EnvVar(node.asText())
            }
            node.isObject && node.has("variable") -> CacheConfigValue.EnvVar(node.get("variable").asText())
            else -> throw IllegalArgumentException("Invalid CacheConfigValue format")
        }
    }
}

data class DataSourceCacheConfig(
    val evictionIntervalMs: CacheConfigValue = CacheConfigValue.Literal(300000L), // 5 minutes
    val expirationMs: CacheConfigValue = CacheConfigValue.Literal(1800000L), // 30 minutes
    val maxSize: CacheConfigValue = CacheConfigValue.Literal(50L)
) {
    fun resolveEvictionIntervalMs(): Long = evictionIntervalMs.resolve(300000L)
    fun resolveExpirationMs(): Long = expirationMs.resolve(1800000L)
    fun resolveMaxSize(): Int = maxSize.resolve(50L).toInt()
}

data class ConnectorConfiguration(
    val jdbcUrl: JdbcUrlConfig = JdbcUrlConfig.EnvVar("JDBC_URL"),
    val jdbcProperties: Map<String, Any> = emptyMap(),
    val schemas: List<String> = emptyList(),
    val tables: List<TableSchemaRow> = emptyList(),
    val functions: List<FunctionSchemaRow> = emptyList(),
    val nativeQueries: MutableMap<String, NativeQueryInfo> = mutableMapOf(),
    val dataSourceCache: DataSourceCacheConfig = DataSourceCacheConfig()
) {

    object Loader {
        private val mapper = jacksonObjectMapper()

        private const val DEFAULT_CONFIG_DIRECTORY = "/etc/connector"
        private const val CONFIG_FILE_NAME = "configuration.json"

        val CONFIG_DIRECTORY: String = System.getenv("HASURA_CONFIGURATION_DIRECTORY") ?: DEFAULT_CONFIG_DIRECTORY
        val config: ConnectorConfiguration = loadConfigFile(getConfigFilePath())

        fun getConfigFilePath(): Path {
            val configDir = CONFIG_DIRECTORY
            return Path.of(configDir, CONFIG_FILE_NAME)
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
