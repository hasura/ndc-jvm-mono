package io.hasura

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain


@QuarkusMain
object Application {

    @JvmStatic
    fun main(args: Array<String>) {
        Quarkus.run(MyApp::class.java, *args)
    }

    class MyApp : QuarkusApplication {
        override fun run(vararg args: String): Int {
            disableOtelJdbcSanitizer()
            Quarkus.waitForExit()
            return 0
        }

        // Disables stripping of whitespace and replacement of literals in SQL queries with "?"
        // See: https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/6610
        private fun disableOtelJdbcSanitizer() {
            System.setProperty("otel.instrumentation.common.db-statement-sanitizer.enabled", "false")
        }
    }

}
