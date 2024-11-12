package io.hasura.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.hasura.ndc.common.ConnectorConfiguration
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

enum class DatabaseType {
    ORACLE,
    MYSQL,
    SNOWFLAKE
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
            arity = "1",
            paramLabel = "<jdbcUrl>",
            description = ["JDBC URL to connect to the Oracle database"]
        )
        jdbcUrl: String,
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
        schemas: List<String>?
    ) {
        val configFilePath = ConnectorConfiguration.Loader.getConfigFilePath()
        val existingConfig = ConnectorConfiguration.Loader.config

        println("Checking for configuration file at $configFilePath")
        if (existingConfig == ConnectorConfiguration()) {
            println("Non-existent or empty configuration file detected")
        } else {
            println("Existing configuration file detected")
        }

        val configGenerator = when (database) {
            DatabaseType.ORACLE -> OracleConfigGenerator
            DatabaseType.MYSQL -> MySQLConfigGenerator
            DatabaseType.SNOWFLAKE -> SnowflakeConfigGenerator
        }

        println("Generating configuration for $database database...")
        val introspectedConfig = configGenerator.getConfig(
            jdbcUrl = jdbcUrl,
            schemas = schemas ?: emptyList()
        )
        val mergedConfigWithNativeQueries = introspectedConfig.copy(
            nativeQueries = existingConfig.nativeQueries
        )

        val outfilePath = Path.of(ConnectorConfiguration.Loader.CONFIG_DIRECTORY, outfile)
        println("Writing configuration to file: $configFilePath")

        val file = configFilePath.toFile()
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, mergedConfigWithNativeQueries)
        } catch (e: Exception) {
            println("Error writing configuration to file: ${e.message}")

            val parentDir = file.parentFile
            val permissions =  Files.getPosixFilePermissions(parentDir.toPath())
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
