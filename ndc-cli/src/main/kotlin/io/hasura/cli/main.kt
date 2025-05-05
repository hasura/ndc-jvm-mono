package io.hasura.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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


@Command(
    name = "NDC CLI",
    subcommands = [HelpCommand::class],
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
    ) {
        val file = File(outfile)

        // Parse existing configuration
        val existingConfig = if (file.exists()) {
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
        val introspectedConfig = configGenerator.getConfig(
            jdbcUrlConfig = jdbcUrlConfig,
            schemas = schemas ?: existingConfig?.schemas ?: emptyList(),
            fullyQualifyNames = fullyQualifyNames
        )

        val finalConfig = introspectedConfig.copy(
            jdbcUrl = jdbcUrlConfig,
            nativeQueries = existingConfig?.nativeQueries ?: mutableMapOf()
        )

        try {
            println("Writing updated configuration to ${file.absolutePath}")
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, finalConfig)
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

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val cli = CommandLine(CLI())
            val exitCode = cli.execute(*args)
            exitProcess(exitCode)
        }
    }
}
