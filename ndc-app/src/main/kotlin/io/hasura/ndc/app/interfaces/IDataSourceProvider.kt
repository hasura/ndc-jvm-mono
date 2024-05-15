package io.hasura.ndc.app.interfaces

import io.hasura.ndc.common.ConnectorConfiguration
import javax.sql.DataSource

interface IDataSourceProvider {
    fun getDataSource(config: ConnectorConfiguration): DataSource
}