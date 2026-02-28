package org.zephbyte.resourcepackprofiles.client.profile

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object ProfileManager {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath: Path = Path.of("config", "resourcepackprofiles.json")
    private val profiles = mutableMapOf<String, ResourcePackProfile>()
    private var lastActiveProfileName: String? = null

    fun load() {
        profiles.clear()
        if (Files.exists(configPath)) {
            try {
                val json = Files.readString(configPath)
                val type = object : TypeToken<List<ResourcePackProfile>>() {}.type
                val loaded: List<ResourcePackProfile> = gson.fromJson(json, type) ?: emptyList()
                loaded.forEach { profiles[it.name] = it }
                logger.info("Loaded {} profile(s) from config", profiles.size)
            } catch (e: Exception) {
                logger.error("Failed to load profiles config", e)
                profiles.clear()
            }
        }
    }

    private fun save() {
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, gson.toJson(profiles.values.toList()))
    }

    fun hasProfile(name: String): Boolean = name in profiles

    fun getProfiles(): List<ResourcePackProfile> {
        return profiles.values.sortedWith(compareByDescending<ResourcePackProfile> { it.favorite }.thenBy { it.name.lowercase() })
    }

    fun toggleFavorite(name: String) {
        val profile = profiles[name] ?: return
        profiles[name] = profile.copy(favorite = !profile.favorite)
        save()
    }

    fun saveCurrentAsProfile(name: String) {
        val client = MinecraftClient.getInstance()
        val optionsPacks = client.options.resourcePacks.toList()
        logger.info("Saving profile '{}' with {} packs: {}", name, optionsPacks.size, optionsPacks)

        val existing = profiles[name]
        profiles[name] = ResourcePackProfile(name, optionsPacks, existing?.customIcon)
        lastActiveProfileName = name
        save()
        ProfileIconManager.invalidate(name)
    }

    fun deleteProfile(name: String) {
        val profile = profiles.remove(name)
        if (profile != null) {
            ProfileIconManager.deleteCustomIcon(profile)
        }
        if (lastActiveProfileName == name) lastActiveProfileName = null
        save()
    }

    fun updateProfile(name: String, newPackIds: List<String>) {
        val profile = profiles[name] ?: return
        profiles[name] = profile.copy(packIds = newPackIds)
        save()
        ProfileIconManager.invalidate(name)
    }

    fun renameProfile(oldName: String, newName: String): Boolean {
        if (oldName == newName) return true
        if (newName in profiles || oldName !in profiles) return false

        val profile = profiles.remove(oldName) ?: return false
        profiles[newName] = profile.copy(name = newName)

        // Rename custom icon file on disk if it exists
        if (profile.customIcon != null) {
            val iconsDir = Path.of("config", "resourcepackprofiles", "icons")
            val oldPath = iconsDir.resolve(profile.customIcon)
            if (Files.exists(oldPath)) {
                val newFileName = "${newName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")}.png"
                val newPath = iconsDir.resolve(newFileName)
                try {
                    Files.move(oldPath, newPath)
                    profiles[newName] = profiles[newName]!!.copy(customIcon = newFileName)
                } catch (e: Exception) {
                    logger.error("Failed to rename custom icon file", e)
                }
            }
        }

        if (lastActiveProfileName == oldName) lastActiveProfileName = newName
        ProfileIconManager.invalidate(oldName)
        save()
        return true
    }

    fun setCustomIcon(name: String, filename: String?) {
        val profile = profiles[name] ?: return
        profiles[name] = profile.copy(customIcon = filename)
        save()
    }

    fun isActiveProfile(profile: ResourcePackProfile): Boolean {
        val client = MinecraftClient.getInstance()
        val currentPacks = client.options.resourcePacks.toList()
        if (profile.packIds != currentPacks) return false
        // If we know which profile was last applied/saved, only mark that one
        if (lastActiveProfileName != null) return profile.name == lastActiveProfileName
        // Fallback: first matching profile wins (no save/load yet this session)
        return ProfileManager.getProfiles().first { it.packIds == currentPacks }.name == profile.name
    }

    fun applyProfile(profile: ResourcePackProfile): List<String> {
        val client = MinecraftClient.getInstance()
        val availableIds = client.resourcePackManager.profiles.map { it.id }.toSet()
        logger.info("Available pack IDs: {}", availableIds)
        logger.info("Profile '{}' wants pack IDs: {}", profile.name, profile.packIds)

        val missingIds = profile.packIds.filter { it !in availableIds }
        if (missingIds.isNotEmpty()) {
            logger.warn("Missing packs: {}", missingIds)
        }

        val validPacks = profile.packIds.filter { it in availableIds }
        logger.info("Valid packs to apply: {}", validPacks)

        logger.info("options.resourcePacks BEFORE: {}", client.options.resourcePacks.toList())
        client.options.resourcePacks.clear()
        client.options.resourcePacks.addAll(validPacks)
        logger.info("options.resourcePacks AFTER: {}", client.options.resourcePacks.toList())

        client.options.write()
        client.resourcePackManager.scanPacks()
        client.resourcePackManager.setEnabledProfiles(validPacks)

        val enabledAfter = client.resourcePackManager.enabledProfiles.map { it.id }
        logger.info("resourcePackManager.enabledProfiles AFTER setEnabledProfiles: {}", enabledAfter)

        client.reloadResources()
        lastActiveProfileName = profile.name

        return missingIds
    }
}
