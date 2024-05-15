package io.hasura.ndc.app.services

import io.agroal.api.AgroalDataSource
import io.agroal.api.AgroalPoolInterceptor
import io.agroal.api.configuration.AgroalConnectionPoolConfiguration
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier
import io.agroal.api.security.NamePrincipal
import io.agroal.api.security.SimplePassword
import io.hasura.ndc.app.models.ConnectorConfiguration
import io.opentelemetry.instrumentation.annotations.WithSpan
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

@ConfigMapping(prefix = "hasura.agroal")
interface AgroalConfig {

    @WithName("connection_pool_configuration")
    fun connectionPoolConfiguration(): ConnectionPoolConfiguration

    @WithName("connection_factory_configuration")
    fun connectionFactoryConfiguration(): ConnectionFactoryConfiguration

    // Please read: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
    interface ConnectionPoolConfiguration {
        // The minimum number of connections present on the pool.
        // The default is zero and must not be negative. Also, it has to be smaller than max.
        // This value can be changed during runtime, meaning the actual number of connection in the pool may be smaller than the minimum for some periods of time.
        // In those circumstances Agroal chooses to create new connections instead of handing the ones already pooled.
        @WithName("min_pool_size")
        @WithDefault("0")
        fun minPoolSize(): Int

        // maxSize(int) - The maximum number of connections present on the pool.
        // This is a required value that must not be negative.
        // This value can be changed during runtime, meaning the actual number of connections on the pool can be greater than the maximum for some periods of time.
        // In those circumstances Agroal does flush connections as soon as they are returned to the pool.
        @WithName("max_pool_size")
        @WithDefault("10")
        fun maxPoolSize(): Int

        // initialSize(int) - The number of connections added to the pool when it is started.
        // The default is zero and must not be negative. It’s not required that is a value between min and max sizes.
        @WithName("initial_pool_size")
        @WithDefault("0")
        fun initialPoolSize(): Int

        // flushOnClose(boolean) - This setting was added in 1.6 and allows for connections to be flushed upon return to the pool.
        // It’s not enabled by default.
        @WithName("flush_on_close")
        @WithDefault("false")
        fun flushOnClose(): Boolean

        // acquisitionTimeout(Duration) - The maximum amount of time a thread can wait for a connection, after which an exception is thrown instead.
        // See also the loginTimeout setting.
        // The default is zero, meaning a thread will wait indefinitely.
        // This property can be changed during runtime.
        @WithName("acquisition_timeout")
        @WithDefault("PT0S")
        fun acquisitionTimeout(): Duration

        // idleValidationTimeout(Duration) - A foreground validation is executed if a connection has been idle on the pool for longer than this duration.
        // The default is zero, meaning that foreground validation is not performed.
        @WithName("idle_validation_timeout")
        @WithDefault("PT0S")
        fun idleValidationTimeout(): Duration

        // leakTimeout(Duration) - The duration of time a connection can be held without causing a leak to be reported.
        // The default is zero, meaning that Agroal does not check for connection leaks.
        @WithName("leak_timeout")
        @WithDefault("PT0S")
        fun leakTimeout(): Duration

        // validationTimeout(Duration) - The interval between background validation checks.
        // The default is zero, meaning background validation is not performed.
        @WithName("validation_timeout")
        @WithDefault("PT0S")
        fun validationTimeout(): Duration

        // reapTimeout(Duration) - The duration for eviction of idle connections.
        // The default is zero, meaning connections are never considered to be idle.
        @WithName("reap_timeout")
        @WithDefault("PT0S")
        fun reapTimeout(): Duration

        // maxLifetime(Duration) - The maximum amount of time a connection can live, after which it is removed from the pool.
        // The default is zero, meaning this feature is disabled.
        @WithName("max_lifetime")
        @WithDefault("PT0S")
        fun maxLifetime(): Duration

        // enhancedLeakReport(boolean) - Provides detailed insights of the connection status when it’s reported as a leak (as INFO messages on AgroalDataSourceListener).
        // Added on 1.10 and not enabled by default.
        @WithName("enhanced_leak_report")
        @WithDefault("false")
        fun enhancedLeakReport(): Boolean

        // multipleAcquisition(MultipleAcquisitionAction) - Behaviour when a thread tries to acquire multiple connections.
        // Default is to allow, can also warn or throw exception.
        // This setting was added on 1.10.
        @WithName("multiple_acquisition")
        @WithDefault("OFF")
        fun multipleAcquisition(): AgroalConnectionPoolConfiguration.MultipleAcquisitionAction

        // transactionRequirement(TransactionRequirement) - Requirement for enlisting connection with running transaction.
        // The default is to not require enlistment, can also warn or throw exception.
        // This setting was added on 1.10.
        @WithName("transaction_requirement")
        @WithDefault("OFF")
        fun transactionRequirement(): AgroalConnectionPoolConfiguration.TransactionRequirement
    }

    interface ConnectionFactoryConfiguration {
        // loginTimeout(Duration) - Since 1.16 this setting allows a timeout when attempting to establish a new connection.
        // Because of limitations on the JDBC API, the resolution of this property is in seconds.
        // A thread wanting to get a connection in pool-less mode may need to wait for this timeout in addition to the acquisition timeout.
        // For regular pool operation both timeouts are concurrent and therefore is suggested that the acquisition timeout should be longer than the login timeout.
        // The default value is zero, and that waits indefinitely.
        @WithName("login_timeout")
        @WithDefault("PT0S")
        fun loginTimeout(): Duration
    }
}

@Singleton
class AgroalDataSourceService {

    @Inject
    private lateinit var logger: Logger

    @Inject
    private lateinit var config: AgroalConfig

    fun createDataSource(
        connConfig: ConnectorConfiguration
    ): DataSource {
        val configSupplier = mkAgroalDataSourceConfigurationSupplier(connConfig.jdbcUrl, connConfig.jdbcProperties)
        val ds = AgroalDataSource.from(configSupplier).apply {
            loginTimeout = config.connectionFactoryConfiguration().loginTimeout().toSeconds().toInt()
            setPoolInterceptors(listOf(PoolInterceptor))
        }
        return ds
    }

    /** Creates a JDBC DataSource wrapped in an [io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource] */
    fun createTracingDataSource(
        connConfig: ConnectorConfiguration
    ): DataSource {
        val agroalDs = createDataSource(connConfig)
        return OpenTelemetryDataSource(agroalDs)
    }

    @WithSpan
    fun createDataSourceFromConnInfo(
        connConfig: ConnectorConfiguration,
        tracing: Boolean = true
    ): DataSource {
        return when (tracing) {
            true -> createTracingDataSource(connConfig)
            false -> createDataSource(connConfig)
        }
    }

    private object PoolInterceptor : AgroalPoolInterceptor {
        private val logger = Logger.getLogger(PoolInterceptor::class.java.name)

        override fun onConnectionAcquire(connection: Connection) {
            logger.debug("Acquired connection: ${connection.metaData.driverName}")
            super.onConnectionAcquire(connection)
        }

        override fun onConnectionReturn(connection: Connection) {
            logger.debug("Returned connection: ${connection.metaData.driverName}")
            super.onConnectionReturn(connection)
        }
    }

    private fun mkAgroalDataSourceConfigurationSupplier(
        jdbcUrl: String,
        properties: Map<String, Any>
    ) =
        AgroalDataSourceConfigurationSupplier().metricsEnabled().connectionPoolConfiguration { connectionPool ->
            connectionPool
                .initialSize(config.connectionPoolConfiguration().initialPoolSize())
                .minSize(config.connectionPoolConfiguration().minPoolSize())
                .maxSize(config.connectionPoolConfiguration().maxPoolSize())
                .flushOnClose(config.connectionPoolConfiguration().flushOnClose())
                .leakTimeout(config.connectionPoolConfiguration().leakTimeout())
                .reapTimeout(config.connectionPoolConfiguration().reapTimeout())
                .validationTimeout(config.connectionPoolConfiguration().validationTimeout())
                .acquisitionTimeout(config.connectionPoolConfiguration().acquisitionTimeout())
                .idleValidationTimeout(config.connectionPoolConfiguration().idleValidationTimeout())
                .transactionRequirement(config.connectionPoolConfiguration().transactionRequirement())
                .maxLifetime(config.connectionPoolConfiguration().maxLifetime())
                .multipleAcquisition(config.connectionPoolConfiguration().multipleAcquisition())
                .enhancedLeakReport(config.connectionPoolConfiguration().enhancedLeakReport())
                .connectionFactoryConfiguration { connFactory ->
                    connFactory.jdbcUrl(jdbcUrl)
                    connFactory.loginTimeout(config.connectionFactoryConfiguration().loginTimeout())
                    // To explain what is going on here:
                    //
                    // Agroal won't let "user" and "password" be passed in as properties, it requires that you
                    // set credentials using special "Principal" and "Credential" methods.
                    //
                    // So we need to filter the properties to remove "user" and "password" and then set them
                    // using the special methods.
                    //
                    // See: Note at the bottom of this page, under "jdbcProperty(String, String)"
                    //      https://agroal.github.io/docs.html
                    //
                    //      "NOTE: username and password properties are not allowed, these have to be set using the principal / credentials mechanism.
                    fun setProps(props: Map<String, Any>) {
                        props.forEach {
                            val valueAsString = when (it.value) {
                                is String -> it.value as String
                                is Number -> it.value.toString()
                                is Boolean -> it.value.toString()
                                else -> throw IllegalArgumentException("Unsupported type for property ${it.key}: ${it.value::class}")
                            }
                            connFactory.jdbcProperty(it.key, valueAsString)
                        }
                    }
                    if (properties.containsKey("user") || properties.containsKey("password")) {
                        connFactory.principal(NamePrincipal(properties["user"]!! as String))
                        connFactory.credential(SimplePassword(properties["password"]!! as String))
                        setProps(properties.filterKeys { it != "user" && it != "password" && it != "include_schemas" })
                    } else {
                        setProps(properties.filterKeys { it != "include_schemas" })
                    }
                    connFactory
                }
        }
}
