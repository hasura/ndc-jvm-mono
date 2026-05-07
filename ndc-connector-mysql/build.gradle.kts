import org.jetbrains.kotlin.builtins.StandardNames.FqNames.set

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
        credentials {
            username = env.fetch("JOOQ_PRO_EMAIL")
            password = env.fetch("JOOQ_PRO_LICENSE")
        }
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

    // JDBC driver
    implementation("io.quarkus:quarkus-jdbc-mysql")

    implementation("org.jooq.pro:jooq:3.19.8")
    modules {
        module("org.jooq:jooq") {
            replacedBy("org.jooq.pro:jooq", "Oracle requires jOOQ pro")
        }
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
}

// Share queryRequest JSON fixtures from :ndc-ir test resources so connector tests can read
// them off the classpath without duplicating files.
sourceSets {
    test {
        resources {
            srcDir(rootProject.file("ndc-ir/src/test/resources"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // Point ConnectorConfiguration.Loader at the test fixture before it lazily initialises.
    environment(
        "HASURA_CONFIGURATION_DIRECTORY",
        rootProject.file("ndc-sqlgen/src/test/resources/connector-config").absolutePath
    )
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


