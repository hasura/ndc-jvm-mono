plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "io.hasura"
version = properties["version"] as String


repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    api(project(":ndc-ir"))
    api("org.jooq:jooq:3.19.8")

    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    implementation("com.google.guava:guava:33.0.0-jre") {
        because("Used in QueryRequestRelationGraph to create a graph of table relations")
    }

    api(kotlin("script-runtime"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
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
