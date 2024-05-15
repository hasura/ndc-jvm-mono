package io.hasura.ndc.app.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.hasura.ndc.common.ConnectorConfiguration
import java.io.File
import java.nio.file.Path

object ConnectorConfigurationLoader {
    private val mapper = jacksonObjectMapper()

    private val DEFAULT_CONFIG_DIRECTORY = "/etc/connector"
    private val ENV_SUPPLIED_CONFIG_DIRECTORY = System.getenv("HASURA_CONFIGURATION_DIRECTORY")
    private val CONFIG_FILE_NAME = "connector.config.json"

    val config: ConnectorConfiguration

    init {
        val configPath = getConfigPath()
        println("Loading configuration from $configPath")
        config = loadConfigFile(configPath)
    }

    private fun getConfigPath(): Path {
        val configDirectory = ENV_SUPPLIED_CONFIG_DIRECTORY ?: DEFAULT_CONFIG_DIRECTORY
        return Path.of(configDirectory, CONFIG_FILE_NAME)
    }

    private fun loadConfigFile(path: Path): ConnectorConfiguration {
        return mapper.readValue(File(path.toString()), ConnectorConfiguration::class.java)
    }
}