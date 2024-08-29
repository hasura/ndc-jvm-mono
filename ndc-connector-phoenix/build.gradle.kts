plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    id("io.quarkus")
}

group = "io.hasura"
version = properties["version"] as String

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://repo.jooq.org/repo")
    }
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))

    // Hasura app components
    implementation(project(":ndc-ir"))
    implementation(project(":ndc-app"))

    // Phoenix JDBC driver
    implementation(files("./lib/phoenix-client-embedded-hbase-2.5-5.2.0.jar"))
    implementation("org.jooq:jooq:3.19.8")
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_21.toString()
    kotlinOptions.javaParameters = true
}

tasks.quarkusDev.configure {
    jvmArgs = listOf(
        "-Djava.net.preferIPv4Stack=true",
        "-Djava.net.preferIPv4Addresses=true",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED"
    )
}


java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}