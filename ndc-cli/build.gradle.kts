import org.gradle.kotlin.dsl.support.kotlinCompilerOptions

plugins {
    `kotlin-dsl`
    application
}

group = "io.hasura"
version = properties["version"] as String


repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(project(":ndc-ir"))
    implementation("org.jooq:jooq:3.19.8")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    implementation("info.picocli:picocli:4.7.5")

    implementation("com.oracle.database.jdbc:ojdbc8:19.18.0.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("net.snowflake:snowflake-jdbc:3.16.1")

    implementation("org.apache.phoenix:phoenix-client-hbase-2.4:5.1.1")
    implementation("org.apache.phoenix:phoenix-queryserver-client:5.0.0-HBase-2.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_21.toString()
    kotlinOptions.javaParameters = true
}

java {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass = "io.hasura.cli.CLI"
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )
}
