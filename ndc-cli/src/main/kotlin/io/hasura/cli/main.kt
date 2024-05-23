package io.hasura.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import picocli.CommandLine
import picocli.CommandLine.*
import java.io.File
import kotlin.system.exitProcess

enum class DatabaseType {
    ORACLE,
    MYSQL
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
        database: DatabaseType?,
        @Option(
            names = ["-s", "--schemas"],
            description = ["Comma-separated list of schemas to introspect"]
        )
        schemas: String?,
    ) {

        val configGenerator = when (database ?: DatabaseType.ORACLE) {
            DatabaseType.ORACLE -> OracleConfigGenerator
            DatabaseType.MYSQL -> MySQLConfigGenerator
        }

        val config = configGenerator.getConfig(
            jdbcUrl = jdbcUrl,
            schemas = schemas?.split(",") ?: emptyList()
        )

        mapper.writerWithDefaultPrettyPrinter().writeValue(File(outfile),config)
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

fun main(args: Array<String>) {
    val cli = CommandLine(CLI())
    val exitCode = cli.execute(*args)
    exitProcess(exitCode)
}
