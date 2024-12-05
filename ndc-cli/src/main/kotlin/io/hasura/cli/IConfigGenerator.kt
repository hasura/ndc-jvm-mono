package io.hasura.cli

import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.JdbcUrlConfig

interface IConfigGenerator {
    fun getConfig(jdbcUrlConfig: JdbcUrlConfig, schemas: List<String>): ConnectorConfiguration
}