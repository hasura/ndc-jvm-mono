package io.hasura.cli

import io.hasura.ndc.common.ConnectorConfiguration

interface IConfigGenerator {
    fun getConfig(jdbcUrl: String, schemas: List<String>): ConnectorConfiguration
}