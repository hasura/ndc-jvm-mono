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
            Quarkus.waitForExit()
            return 0
        }
    }

}
