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

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
