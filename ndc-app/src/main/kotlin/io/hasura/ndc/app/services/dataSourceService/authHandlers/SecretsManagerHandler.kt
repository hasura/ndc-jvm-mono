package io.hasura.services.dataSourceService.authHandlers

import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier
import io.agroal.api.security.NamePrincipal

class SecretsManagerHandler(
    override val jdbcUrl: String,
    override val properties: Map<String, Any>,
    override val connFactory: AgroalConnectionFactoryConfigurationSupplier
) : BaseAuthHandler(jdbcUrl, properties, connFactory) {

    override fun configFactory() {
        requireUser()
        when {
            jdbcUrl.contains("jdbc-secretsmanager:mysql") ->
                connFactory.connectionProviderClass(com.amazonaws.secretsmanager.sql.AWSSecretsManagerMySQLDriver::class.java)
            jdbcUrl.contains("jdbc-secretsmanager:oracle") ->
                connFactory.connectionProviderClass(com.amazonaws.secretsmanager.sql.AWSSecretsManagerOracleDriver::class.java)
            else -> throw Exception()
        }
        connFactory.principal(NamePrincipal(properties["user"]!! as String))
        setProps(properties)
    }
}
