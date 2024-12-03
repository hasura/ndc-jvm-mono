package io.hasura.ndc.common

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.Path

data class ConnectorConfiguration(
    val jdbcUrl: String = "",
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