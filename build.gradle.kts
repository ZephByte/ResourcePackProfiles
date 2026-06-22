import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    id("fabric-loom") version "1.16-SNAPSHOT"
    id("me.modmuss50.mod-publish-plugin") version "0.8.4"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("resourcepackprofiles") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

repositories {
    maven("https://maven.terraformersmc.com/")
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    // 26.x ships unobfuscated, so there are no mappings to apply; intermediary v0.0.0 is an
    // identity passthrough that satisfies Loom's requirement for a non-empty `mappings` config.
    mappings("net.fabricmc:intermediary:0.0.0:v2")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    // ModMenu 18.0.0-beta.1 targets 26.1.x; alpha builds target 1.21.x and 19.x+ require MC >=26.2.
    implementation("com.terraformersmc:modmenu:18.0.0-beta.1")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version") as String,
            "loader_version" to project.property("loader_version") as String,
            "kotlin_loader_version" to project.property("kotlin_loader_version") as String
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE.txt") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

val modVersion = project.version as String
val gameVersionList = (project.property("modrinth_versions") as String).split(",").map { it.trim() }

publishMods {
    file.set(tasks.remapJar.flatMap { it.archiveFile })
    version.set(modVersion)
    changelog.set(providers.environmentVariable("CHANGELOG").orElse(""))
    type.set(
        when {
            modVersion.contains("-alpha") -> ALPHA
            modVersion.contains("-beta")  -> BETA
            else                          -> STABLE
        }
    )
    modLoaders.add("fabric")

    modrinth {
        accessToken.set(providers.environmentVariable("MODRINTH_TOKEN").orElse(""))
        // Modrinth project ID (not the slug — the plugin validates the ID format)
        projectId.set(providers.gradleProperty("modrinth_id").orElse(""))
        minecraftVersions.addAll(gameVersionList)
        requires { slug.set("fabric-api") }
        requires { slug.set("fabric-language-kotlin") }
        optional { slug.set("modmenu") }
    }

    curseforge {
        accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN").orElse(""))
        // Numeric CurseForge project ID, set in gradle.properties once the project exists.
        projectId.set(providers.gradleProperty("curseforge_id").orElse(""))
        minecraftVersions.addAll(gameVersionList)
        requires { slug.set("fabric-api") }
        requires { slug.set("fabric-language-kotlin") }
        optional { slug.set("modmenu") }
    }
}
