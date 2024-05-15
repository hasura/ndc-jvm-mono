package io.hasura.ndc.app.interfaces

import io.hasura.ndc.app.models.ConnectorConfiguration
import javax.sql.DataSource

interface IDataSourceProvider {
    fun getDataSource(config: ConnectorConfiguration): DataSource
}