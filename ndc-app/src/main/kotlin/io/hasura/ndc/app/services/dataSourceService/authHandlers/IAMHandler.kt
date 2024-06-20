package io.hasura.services.dataSourceService.authHandlers

import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier
import io.agroal.api.security.NamePrincipal
import software.amazon.jdbc.PropertyDefinition

class IAMHandler(
    override val jdbcUrl: String,
    override val properties: Map<String, Any>,
    override val connFactory: AgroalConnectionFactoryConfigurationSupplier
) : BaseAuthHandler(jdbcUrl, properties, connFactory) {

    override fun configFactory() {
        requireUser()
        connFactory.principal(NamePrincipal(properties["user"]!! as String))
        connFactory.jdbcProperty(PropertyDefinition.PLUGINS.name, "iam")
        setProps(properties)
    }
}
