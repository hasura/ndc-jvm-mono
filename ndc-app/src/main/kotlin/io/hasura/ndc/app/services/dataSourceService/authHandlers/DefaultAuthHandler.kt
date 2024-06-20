package io.hasura.services.dataSourceService.authHandlers

import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier
import io.agroal.api.security.NamePrincipal
import io.agroal.api.security.SimplePassword

class DefaultAuthHandler(
    override val jdbcUrl: String,
    override val properties: Map<String, Any>,
    override val connFactory: AgroalConnectionFactoryConfigurationSupplier
) : BaseAuthHandler(jdbcUrl, properties, connFactory) {

    override fun configFactory() {
        if (properties.containsKey("user")) {
            connFactory.principal(NamePrincipal(properties["user"]!! as String))
        }
        if (properties.containsKey("password")) {
            connFactory.credential(SimplePassword(properties["password"]!! as String))
        }
        setProps(properties)
    }
}
