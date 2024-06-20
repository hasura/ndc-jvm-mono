package io.hasura.services.dataSourceService.authHandlers

import io.agroal.api.configuration.supplier.AgroalConnectionFactoryConfigurationSupplier

class MissingParameterException(parameter: String) : Exception("Missing JDBC parameter: $parameter")

abstract class BaseAuthHandler(
    open val jdbcUrl: String,
    open val properties: Map<String, Any>,
    open val connFactory: AgroalConnectionFactoryConfigurationSupplier
) {
    protected fun setProps(props: Map<String, Any>) {
        props.filterKeys { it != "user" && it != "password" && it != "include_schemas" }
            .forEach {
                val valueAsString = when (it.value) {
                    is String -> it.value as String
                    is Number -> it.value.toString()
                    is Boolean -> it.value.toString()
                    else -> throw IllegalArgumentException("Unsupported type for property ${it.key}: ${it.value::class}")
                }
                connFactory.jdbcProperty(it.key, valueAsString)
            }
    }

    protected fun requireUser(checkURL: Boolean = false) {
        if (checkURL) {
            if (!properties.containsKey("user") && !jdbcUrl.contains("user")) throw MissingParameterException("user")
        } else {
            if (!properties.containsKey("user")) throw MissingParameterException("user")
        }
    }

    abstract fun configFactory()
}
