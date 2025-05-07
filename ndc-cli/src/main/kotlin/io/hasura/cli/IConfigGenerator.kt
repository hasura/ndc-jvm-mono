package io.hasura.cli

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.JdbcUrlConfig
import java.io.File

interface IConfigGenerator {
    fun generateConfig(
        jdbcUrlConfig: JdbcUrlConfig,
        schemas: List<String>,
        fullyQualifyNames: Boolean = false,
    ): ConnectorConfiguration

}
