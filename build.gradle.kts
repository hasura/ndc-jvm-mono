plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("co.uzzu.dotenv.gradle") version "4.0.0"
}


// Add this block:
tasks.withType<Test> {
    useJUnitPlatform()
}

// Security floor for transitive dependencies (Trivy HIGH/CRITICAL findings, Nov 2026).
// The Quarkus 3.20 LTS platform still pins vulnerable netty/jackson/micrometer/guava versions,
// so raise them to the nearest fixed patch release on the same minor line.
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true ->
                    useVersion("4.1.141.Final")
                requested.group.startsWith("com.fasterxml.jackson") && requested.version?.startsWith("2.") == true ->
                    useVersion("2.18.12")
                requested.group == "io.micrometer" && requested.name in setOf("micrometer-core", "micrometer-commons", "micrometer-observation", "micrometer-registry-prometheus", "micrometer-registry-prometheus-simpleclient") ->
                    useVersion("1.15.12")
                requested.group == "com.google.guava" && requested.name == "guava" ->
                    useVersion("33.7.2-jre")
            }
        }
    }
}
