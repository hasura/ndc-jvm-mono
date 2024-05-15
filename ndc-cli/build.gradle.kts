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
    implementation(project(":ndc-ir"))
    implementation("org.jooq:jooq:3.19.8")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    implementation("info.picocli:picocli:4.7.5")
    implementation("com.oracle.database.jdbc:ojdbc8:19.18.0.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
    kotlinOptions.javaParameters = true
}

java {
    withSourcesJar()
}
