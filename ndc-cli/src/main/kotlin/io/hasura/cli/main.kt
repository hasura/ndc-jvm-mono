package io.hasura.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.ObjectMapper
import io.hasura.ndc.common.ConnectorConfiguration
import io.hasura.ndc.common.JdbcUrlConfig
import picocli.CommandLine
import picocli.CommandLine.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

enum class DatabaseType {
    ORACLE,
    MYSQL,
    SNOWFLAKE,
    TRINO
}

/**
 * Reads an existing connector configuration file if it exists.
 *
 * @param file The configuration file to read
 * @param mapper The ObjectMapper used to deserialize the configuration
 * @return The parsed ConnectorConfiguration or null if the file doesn't exist or can't be parsed
 */
fun readExistingConfiguration(file: File, mapper: ObjectMapper): ConnectorConfiguration? {
    return if (file.exists()) {
        println("Existing configuration file detected at ${file.absolutePath}")
        try {
            mapper.readValue<ConnectorConfiguration>(file)
        } catch (e: Exception) {
            println("Error reading existing configuration: ${e.message}")
            null
        }
    } else {
        println("No existing configuration file found at ${file.absolutePath}")
        null
    }
}



@Command(
    name = "NDC CLI",
    subcommands = [HelpCommand::class, NativeQueriesCommand::class],
    description = ["Hasura V3 Connector Config CLI"]
)
class CLI {
    private val mapper = jacksonObjectMapper()


    // Example:
    // update jdbc:oracle:thin:@//localhost:1521/XE?user=chinook&password=Password123 --database ORACLE
    @Command(
        name = "update",
        description = ["Introspect the database and emit updated schema information"],
        sortSynopsis = false,
        sortOptions = false
    )
    fun update(
        @Parameters(
            arity = "0..1",
            paramLabel = "<jdbcUrl>",
            description = ["JDBC URL to connect to the database (optional)"]
        )
        jdbcUrlParam: String?,
        @Option(
            names = ["-o", "--outfile"],
            defaultValue = "configuration.json",
            description = ["The name of the output file to write the schema information to, defaults to configuration.json"]
        )
        outfile: String,
        @Option(
            names = ["-d", "--database"],
            description = ["Type of the database to introspect"]
        )
        database: DatabaseType,
        @Option(
            names = ["-s", "--schemas"],
            arity = "0..*",
            split = ",",
            description = ["Comma-separated list of schemas to introspect"]
        )
        schemas: List<String>?,
        @Option(
            names = ["-f", "--fully-qualify-names"],
            description = ["Whether to fully qualify table names"]
        )
        fullyQualifyNames: Boolean = false,
        @Option(
            names = ["--cache-eviction-interval"],
            description = ["DataSource cache eviction interval in milliseconds (default: 300000)"]
        )
        cacheEvictionInterval: Long? = null,
        @Option(
            names = ["--cache-expiration"],
            description = ["DataSource cache expiration time in milliseconds (default: 1800000)"]
        )
        cacheExpiration: Long? = null,
        @Option(
            names = ["--cache-max-size"],
            description = ["Maximum number of cached DataSource instances (default: 50)"]
        )
        cacheMaxSize: Long? = null,
    ) {
        val file = File(outfile)

        // Parse existing configuration
        val existingConfig = readExistingConfiguration(file, mapper)

        // Determine the JDBC URL configuration
        val jdbcUrlConfig = when {
            jdbcUrlParam != null -> {
                // If jdbcUrlParam is provided, use it as a literal value
                JdbcUrlConfig.Literal(jdbcUrlParam)
            }
            System.getenv("JDBC_URL") != null -> {
                // If JDBC_URL environment variable is set, use it
                JdbcUrlConfig.EnvVar("JDBC_URL")
            }
            existingConfig?.jdbcUrl != null -> {
                // If there's an existing config, use its jdbcUrl (which could be either Literal or EnvVar)
                existingConfig.jdbcUrl
            }
            else -> {
                // If none of the above conditions are met, throw an error
                throw IllegalArgumentException("No JDBC URL provided and no existing configuration found")
            }
        }

        val configGenerator = when (database) {
            DatabaseType.ORACLE -> OracleConfigGenerator
            DatabaseType.MYSQL -> MySQLConfigGenerator
            DatabaseType.SNOWFLAKE -> SnowflakeConfigGenerator
            DatabaseType.TRINO -> TrinoConfigGenerator
        }

        println("Introspecting database...")
        val introspectedConfig = configGenerator.generateConfig(
            jdbcUrlConfig = jdbcUrlConfig,
            schemas = schemas ?: existingConfig?.schemas ?: emptyList(),
            fullyQualifyNames = fullyQualifyNames
        )

        // Build cache configuration from CLI options or preserve existing
        val cacheConfig = buildDataSourceCacheConfig(
            cacheEvictionInterval = cacheEvictionInterval,
            cacheExpiration = cacheExpiration,
            cacheMaxSize = cacheMaxSize,
            existingConfig = existingConfig?.dataSourceCache
        )

        val finalConfig = introspectedConfig.copy(
            jdbcUrl = jdbcUrlConfig,
            nativeQueries = existingConfig?.nativeQueries ?: mutableMapOf(),
            dataSourceCache = cacheConfig
        )
        writeConfigurationToFile(file, finalConfig, mapper)

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val cli = CommandLine(CLI())
            val exitCode = cli.execute(*args)
            exitProcess(exitCode)
        }
    }



}

/**
 * Builds DataSource cache configuration from CLI options or preserves existing configuration
 */
fun buildDataSourceCacheConfig(
    cacheEvictionInterval: Long?,
    cacheExpiration: Long?,
    cacheMaxSize: Long?,
    existingConfig: io.hasura.ndc.common.DataSourceCacheConfig?
): io.hasura.ndc.common.DataSourceCacheConfig {
    return io.hasura.ndc.common.DataSourceCacheConfig(
        evictionIntervalMs = cacheEvictionInterval?.let {
            io.hasura.ndc.common.CacheConfigValue.Literal(it)
        } ?: existingConfig?.evictionIntervalMs ?: io.hasura.ndc.common.CacheConfigValue.Literal(300000L),

        expirationMs = cacheExpiration?.let {
            io.hasura.ndc.common.CacheConfigValue.Literal(it)
        } ?: existingConfig?.expirationMs ?: io.hasura.ndc.common.CacheConfigValue.Literal(1800000L),

        maxSize = cacheMaxSize?.let {
            io.hasura.ndc.common.CacheConfigValue.Literal(it)
        } ?: existingConfig?.maxSize ?: io.hasura.ndc.common.CacheConfigValue.Literal(50L)
    )
}

/**
 * Writes configuration to a file with proper error handling
 * Exits the process if writing fails
 *
 * @param file The file to write the configuration to
 * @param config The configuration object to write
 * @param mapper The object mapper to use for writing JSON
 */
fun writeConfigurationToFile(
    file: File,
    config: ConnectorConfiguration,
    mapper: com.fasterxml.jackson.databind.ObjectMapper
) {
    try {
        println("Writing updated configuration to ${file.absolutePath}")
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, config)
        println("Configuration updated successfully")
    } catch (e: Exception) {
        println("Error writing configuration to file: ${e.message}")

        val parentDir = file.parentFile
        val permissions = Files.getPosixFilePermissions(parentDir.toPath())
        val posixPermissions = PosixFilePermissions.toString(permissions)

        println("Parent directory: ${parentDir.absolutePath}")
        println("Readable: ${parentDir.canRead()}, Writable: ${parentDir.canWrite()}")
        println("Permissions: $posixPermissions")

        exitProcess(1)
    }
}


@Command(
    name = "native-queries",
    description = ["Manage native queries for the connector"],
    subcommands = [NativeQueriesCreateCommand::class]
)
class NativeQueriesCommand {
    private val mapper = jacksonObjectMapper()
    // This class serves as a container for native-query subcommands
}

@Command(
    name = "create",
    description = ["Create a new native query"],
    sortSynopsis = false,
    sortOptions = false
)
class NativeQueriesCreateCommand : Runnable {

    private val mapper = jacksonObjectMapper()


    @Option(
            names = ["-c", "--configuration-dir"],
            description = ["Directory containing the configuration files"],
    )
    var configurationDir: String = System.getenv("HASURA_PLUGIN_CONNECTOR_CONTEXT_PATH") ?: "/etc/connector"


    @Option(
        names = ["--operation-path"],
        required = true,
        description = ["SQL file path"])
    lateinit var sqlFile: String

    @Option(
        names = ["-n", "--name"],
        required = true,
        description = ["Name for the native query"]
    )
    lateinit var queryName: String

    @Option(
        names = ["-o", "--overwrite"],
        description = ["Overwrite existing query if one exists with the same name"]
    )
    var overwrite: Boolean = false

    @Option(
        names = ["--skip-parameters-metadata"],
        description = ["Skip parameters metadata generation"]
    )
    var skipParametersMetadata: Boolean = false

    @Option(
        names = ["--skip-resultset-metadata"],
        description = ["Skip result set metadata generation"]
    )
    var skipResultSetMetadata: Boolean = false

    override fun run() {
        try {
            val file = File(configurationDir, "configuration.json")
            val existingConfiguration = readExistingConfiguration(file, mapper)

            if (existingConfiguration == null) {
                println("Connector configuration not found. Please introspect the database first.")
                exitProcess(1)
            }

            val newConfig = createNativeQuery(
                connectorConfig = existingConfiguration,
                configurationDir = configurationDir,
                nativeQuerySQLFile = sqlFile,
                overwrite = overwrite,
                nativeQueryName = queryName,
                skipParamatersMetadata = skipParametersMetadata,
                skipResultSetMetadata = skipResultSetMetadata
            )

            writeConfigurationToFile(file, newConfig, mapper)
            println("Native query '$queryName' created successfully")
        } catch (e: Exception) {
            System.err.println("Error creating native query: ${e.message}")
            exitProcess(1)
        }
    }
}
