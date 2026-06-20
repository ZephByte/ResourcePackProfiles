pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK required by the toolchain (Minecraft 26.1.2 needs Java 25).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
