plugins {
    `kotlin-dsl`
}

group = "io.hasura"
version = properties["version"] as String

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
}

java {
    withSourcesJar()
}
