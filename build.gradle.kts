plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
}
allprojects { group = "dev.clapctrl"; version = "0.1.0" }
subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
