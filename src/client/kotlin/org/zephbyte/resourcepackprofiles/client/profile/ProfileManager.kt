package org.zephbyte.resourcepackprofiles.client.profile

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.client.MinecraftClient
import org.lwjgl.BufferUtils
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.tinyfd.TinyFileDialogs
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private data class ConfigData(
    val profiles: List<ResourcePackProfile> = emptyList(),
    val lastActiveProfile: String? = null
)

private data class ExportData(
    val name: String,
    val packIds: List<String>,
    val favorite: Boolean = false,
    val customIcon: String? = null
)

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
                val config: ConfigData = gson.fromJson(json, ConfigData::class.java)
                config.profiles.forEach { profiles[it.name] = it }
                lastActiveProfileName = config.lastActiveProfile
                logger.info("Loaded {} profile(s) from config", profiles.size)
            } catch (e: Exception) {
                logger.error("Failed to load profiles config", e)
                profiles.clear()
            }
        }
    }

    private fun save() {
        Files.createDirectories(configPath.parent)
        val config = ConfigData(profiles.values.toList(), lastActiveProfileName)
        Files.writeString(configPath, gson.toJson(config))
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
        val userPacks = getUserPacks(client.options.resourcePacks.toList())
        logger.info("Saving profile '{}' with {} user packs: {}", name, userPacks.size, userPacks)

        val existing = profiles[name]
        profiles[name] = ResourcePackProfile(name, userPacks, existing?.customIcon, existing?.favorite ?: false)
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

    private fun getUserPacks(packIds: List<String>): List<String> {
        val client = MinecraftClient.getInstance()
        val requiredIds = client.resourcePackManager.profiles
            .filter { it.isRequired || !it.getSource().canBeEnabledLater() }
            .map { it.id }
            .toSet()
        return packIds.filter { it !in requiredIds }
    }

    fun isActiveProfile(profile: ResourcePackProfile): Boolean {
        if (lastActiveProfileName != profile.name) return false
        val client = MinecraftClient.getInstance()
        val currentUserPacks = getUserPacks(client.options.resourcePacks.toList())
        val availableIds = client.resourcePackManager.profiles.map { it.id }.toSet()
        val profileValidPacks = getUserPacks(profile.packIds).filter { it in availableIds }
        return profileValidPacks == currentUserPacks
    }

    fun exportProfile(profile: ResourcePackProfile): Boolean {
        val filterPatterns = arrayOf("*.rpprofile")
        val filterBuf = BufferUtils.createPointerBuffer(filterPatterns.size)
        for (pattern in filterPatterns) {
            filterBuf.put(MemoryUtil.memUTF8(pattern))
        }
        filterBuf.flip()

        val defaultName = "${profile.name}.rpprofile"
        val path = TinyFileDialogs.tinyfd_saveFileDialog(
            "Export Profile",
            defaultName,
            filterBuf,
            "Resource Pack Profile (*.rpprofile)"
        ) ?: return false

        return try {
            val iconBase64 = ProfileIconManager.encodeIconToBase64(profile.name)
            val exportData = ExportData(
                name = profile.name,
                packIds = profile.packIds,
                favorite = profile.favorite,
                customIcon = iconBase64
            )
            Files.writeString(Path.of(path), gson.toJson(exportData))
            logger.info("Exported profile '{}' to {}", profile.name, path)
            true
        } catch (e: Exception) {
            logger.error("Failed to export profile '{}'", profile.name, e)
            false
        }
    }

    /**
     * Opens a file dialog to import a profile. Returns the imported profile name,
     * or null if cancelled/failed. If overwriteConfirmed is false and a profile
     * with the same name exists, returns the conflicting name prefixed with "!".
     */
    fun importProfile(overwriteName: String? = null): String? {
        val filterPatterns = arrayOf("*.rpprofile")
        val filterBuf = BufferUtils.createPointerBuffer(filterPatterns.size)
        for (pattern in filterPatterns) {
            filterBuf.put(MemoryUtil.memUTF8(pattern))
        }
        filterBuf.flip()

        val path = TinyFileDialogs.tinyfd_openFileDialog(
            "Import Profile",
            null,
            filterBuf,
            "Resource Pack Profile (*.rpprofile)",
            false
        ) ?: return null

        return importProfileFromPath(Path.of(path), overwriteName)
    }

    fun importProfileFromPath(filePath: Path, overwriteName: String? = null): String? {
        return try {
            val json = Files.readString(filePath)
            val data: ExportData = gson.fromJson(json, ExportData::class.java)
            val name = overwriteName ?: data.name

            // Check for existing profile if not an overwrite
            if (overwriteName == null && hasProfile(name)) {
                return "!$name"
            }

            // If overwriting, delete old icon first
            if (overwriteName != null) {
                val existing = profiles[name]
                if (existing != null) {
                    ProfileIconManager.deleteCustomIcon(existing)
                }
            }

            profiles[name] = ResourcePackProfile(
                name = name,
                packIds = data.packIds,
                favorite = data.favorite
            )

            // Import custom icon if present
            if (data.customIcon != null) {
                ProfileIconManager.importIconFromBase64(name, data.customIcon)
            }

            save()
            ProfileIconManager.invalidate(name)
            logger.info("Imported profile '{}' with {} packs", name, data.packIds.size)
            name
        } catch (e: Exception) {
            logger.error("Failed to import profile", e)
            null
        }
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

        val validUserPacks = profile.packIds.filter { it in availableIds }

        // Preserve built-in/required packs that are always present
        val builtinPacks = client.options.resourcePacks.filter { it !in getUserPacks(client.options.resourcePacks.toList()) }
        val fullPackList = builtinPacks + validUserPacks
        logger.info("Valid user packs: {}, built-in packs preserved: {}", validUserPacks, builtinPacks)

        logger.info("options.resourcePacks BEFORE: {}", client.options.resourcePacks.toList())
        client.options.resourcePacks.clear()
        client.options.resourcePacks.addAll(fullPackList)
        logger.info("options.resourcePacks AFTER: {}", client.options.resourcePacks.toList())

        client.options.write()
        client.resourcePackManager.scanPacks()
        client.resourcePackManager.setEnabledProfiles(fullPackList)

        val enabledAfter = client.resourcePackManager.enabledProfiles.map { it.id }
        logger.info("resourcePackManager.enabledProfiles AFTER setEnabledProfiles: {}", enabledAfter)

        client.reloadResources()
        lastActiveProfileName = profile.name
        save()

        return missingIds
    }
}
