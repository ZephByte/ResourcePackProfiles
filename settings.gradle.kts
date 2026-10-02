pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://maven.kikugie.dev/releases")
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK required by the toolchain (Minecraft 26.1.2 needs Java 25).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

stonecutter {
    create(rootProject) {
        versions("26.1", "26.2", "26.3")
        vcsVersion = "26.3"
    }
}

rootProject.name = "ResourcePackProfiles"
