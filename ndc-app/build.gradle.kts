plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    id("io.quarkus")
    `maven-publish`
}

group = "io.hasura"
version = properties["version"] as String

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    api(project(":ndc-sqlgen"))

    api(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))
    api("io.quarkus:quarkus-agroal")
    api("io.quarkus:quarkus-arc")
    api("io.quarkus:quarkus-cache")
    api("io.quarkus:quarkus-kotlin")
    api("io.quarkus:quarkus-micrometer-registry-prometheus")
    api("io.quarkus:quarkus-resteasy-reactive")
    api("io.quarkus:quarkus-resteasy-reactive-jackson")
    api("io.quarkus:quarkus-smallrye-fault-tolerance")
    api("io.quarkus:quarkus-smallrye-openapi")
    api("io.quarkus:quarkus-vertx")
    api("io.quarkus:quarkus-reactive-routes")
    api("io.quarkus:quarkus-logging-json")

    api("io.quarkus:quarkus-opentelemetry")
    api("io.opentelemetry:opentelemetry-extension-kotlin")
    api("io.opentelemetry:opentelemetry-extension-trace-propagators")
    api("io.opentelemetry.instrumentation:opentelemetry-jdbc")

    // //////////////////////
    // Test Dependencies
    // //////////////////////
    testImplementation("io.quarkus:quarkus-junit5")

    // RestAssured
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.rest-assured:kotlin-extensions")
    implementation(kotlin("stdlib-jdk8"))
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

tasks.quarkusDev.configure {
    jvmArgs = listOf(
        "-Djava.net.preferIPv4Stack=true",
        "-Djava.net.preferIPv4Addresses=true",
    )
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
    kotlinOptions.javaParameters = true
}

java {
    withSourcesJar()
}
kotlin {
    jvmToolchain(17)
}