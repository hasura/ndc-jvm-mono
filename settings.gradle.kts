pluginManagement {
    val quarkusPluginVersion: String by settings
    val quarkusPluginId: String by settings

    plugins {
        id(quarkusPluginId) version quarkusPluginVersion
        kotlin("jvm") version "1.9.22"
        kotlin("plugin.allopen") version "1.9.22"
    }
}

rootProject.name = "ndc-jvm-mono"

include(":ndc-ir")
include(":ndc-sqlgen")
include(":ndc-app")
include(":ndc-cli")
include(":ndc-connector-oracle")