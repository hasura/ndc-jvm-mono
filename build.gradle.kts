plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("co.uzzu.dotenv.gradle") version "4.0.0"
}


// Add this block:
tasks.withType<Test> {
    useJUnitPlatform()
}
