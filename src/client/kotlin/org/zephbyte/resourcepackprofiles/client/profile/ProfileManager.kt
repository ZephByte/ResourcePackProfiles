package org.zephbyte.resourcepackprofiles.client.profile

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.util.FileDialogs
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

/** Outcome of importing a profile from a file. */
sealed interface ImportResult {
    /** The profile was imported (or overwritten) under [name]. */
    data class Imported(val name: String) : ImportResult
    /** A profile named [name] already exists; importing would overwrite it. */
    data class Conflict(val name: String) : ImportResult
    /** The file could not be read or parsed. */
    data object Failed : ImportResult
}

object ProfileManager {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath: Path = Path.of("config", "resourcepackprofiles.json")
    private val iconsDir: Path = Path.of("config", "resourcepackprofiles", "icons")
    private val profileFilters = arrayOf("*.rpprofile")
    private const val PROFILE_FILTER_DESC = "Resource Pack Profile (*.rpprofile)"
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
        try {
            Files.createDirectories(configPath.parent)
            val config = ConfigData(profiles.values.toList(), lastActiveProfileName)
            Files.writeString(configPath, gson.toJson(config))
        } catch (e: Exception) {
            logger.error("Failed to save profiles config", e)
        }
    }

    fun hasProfile(name: String): Boolean = name in profiles

    fun getProfiles(): List<ResourcePackProfile> {
        return profiles.values.sortedWith(
            compareByDescending<ResourcePackProfile> { it.favorite }
                .thenComparator { a, b -> naturalCompare(a.name, b.name) }
        )
    }

    /**
     * Case-insensitive comparison that orders embedded numbers by value rather than lexically,
     * so e.g. "t9" sorts before "t10".
     */
    private fun naturalCompare(s1: String, s2: String): Int {
        val a = s1.lowercase()
        val b = s2.lowercase()
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                while (ni < a.length && a[ni].isDigit()) ni++
                var nj = j
                while (nj < b.length && b[nj].isDigit()) nj++
                val na = a.substring(i, ni).trimStart('0')
                val nb = b.substring(j, nj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ni
                j = nj
            } else {
                if (ca != cb) return ca.code - cb.code
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun toggleFavorite(name: String) {
        val profile = profiles[name] ?: return
        profiles[name] = profile.copy(favorite = !profile.favorite)
        save()
    }

    fun saveCurrentAsProfile(name: String) {
        val client = Minecraft.getInstance()
        val userPacks = getUserPacks(client.options.resourcePacks.toList())
        logger.debug("Saving profile '{}' with {} user packs: {}", name, userPacks.size, userPacks)

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
            val oldPath = iconsDir.resolve(profile.customIcon)
            if (Files.exists(oldPath)) {
                val newFileName = "${ProfileIconManager.sanitizeFileName(newName)}.png"
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
        val client = Minecraft.getInstance()
        val requiredIds = client.resourcePackRepository.availablePacks
            .filter { it.isRequired || !it.packSource.shouldAddAutomatically() }
            .map { it.id }
            .toSet()
        return packIds.filter { it !in requiredIds }
    }

    fun isActiveProfile(profile: ResourcePackProfile): Boolean {
        if (lastActiveProfileName != profile.name) return false
        val client = Minecraft.getInstance()
        val currentUserPacks = getUserPacks(client.options.resourcePacks.toList())
        val availableIds = client.resourcePackRepository.availablePacks.map { it.id }.toSet()
        val profileValidPacks = getUserPacks(profile.packIds).filter { it in availableIds }
        return profileValidPacks == currentUserPacks
    }

    fun exportProfile(profile: ResourcePackProfile): Boolean {
        val path = FileDialogs.saveFile(
            "Export Profile",
            "${profile.name}.rpprofile",
            profileFilters,
            PROFILE_FILTER_DESC
        ) ?: return false

        return try {
            val iconBase64 = ProfileIconManager.encodeIconToBase64(profile)
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

    /** Opens a file dialog and imports the selected profile. Blocks, so call off the render thread. */
    fun pickProfileToImport(): String? =
        FileDialogs.openFile("Import Profile", profileFilters, PROFILE_FILTER_DESC)

    /**
     * Imports a profile from [filePath]. If [overwriteName] is null and a profile with the file's
     * name already exists, returns [ImportResult.Conflict] without writing anything; pass the name
     * back as [overwriteName] to confirm the overwrite.
     */
    fun importProfileFromPath(filePath: Path, overwriteName: String? = null): ImportResult {
        return try {
            val json = Files.readString(filePath)
            val data: ExportData = gson.fromJson(json, ExportData::class.java)
            val name = overwriteName ?: data.name

            if (overwriteName == null && hasProfile(name)) {
                return ImportResult.Conflict(name)
            }

            // Capture old icon filename before overwriting — used for cleanup after a successful
            // write so we never delete the old file before the new one is confirmed on disk.
            val oldCustomIcon: String? = if (overwriteName != null) profiles[name]?.customIcon else null

            profiles[name] = ResourcePackProfile(
                name = name,
                packIds = data.packIds,
                favorite = data.favorite
            )

            if (data.customIcon != null) {
                // importIconFromBase64 now propagates exceptions: if it throws, save() is skipped
                // and the old icon file remains untouched on disk.
                ProfileIconManager.importIconFromBase64(name, data.customIcon)
                // Icon written successfully. If the old icon had a different filename (edge case
                // after a failed rename), it is now orphaned — clean it up.
                if (oldCustomIcon != null) {
                    val newFileName = "${ProfileIconManager.sanitizeFileName(name)}.png"
                    if (oldCustomIcon != newFileName) {
                        try { Files.deleteIfExists(iconsDir.resolve(oldCustomIcon)) } catch (_: Exception) {}
                    }
                }
            } else if (overwriteName != null && oldCustomIcon != null) {
                // Imported profile carries no icon; remove the existing custom icon file.
                try { Files.deleteIfExists(iconsDir.resolve(oldCustomIcon)) } catch (_: Exception) {}
            }

            save()
            ProfileIconManager.invalidate(name)
            logger.info("Imported profile '{}' with {} packs", name, data.packIds.size)
            ImportResult.Imported(name)
        } catch (e: Exception) {
            logger.error("Failed to import profile", e)
            ImportResult.Failed
        }
    }

    fun applyProfile(profile: ResourcePackProfile): List<String> {
        val client = Minecraft.getInstance()
        val availableIds = client.resourcePackRepository.availablePacks.map { it.id }.toSet()

        val missingIds = profile.packIds.filter { it !in availableIds }
        if (missingIds.isNotEmpty()) {
            logger.warn("Profile '{}' is missing packs: {}", profile.name, missingIds)
        }

        // Strip required/non-auto packs so builtinPacks + validUserPacks never contains duplicates
        // (an imported profile could legally include a required pack id in its list).
        val validUserPacks = getUserPacks(profile.packIds.filter { it in availableIds })

        // Preserve built-in/required packs that are always present
        val builtinPacks = client.options.resourcePacks.filter { it !in getUserPacks(client.options.resourcePacks.toList()) }
        val fullPackList = builtinPacks + validUserPacks
        logger.debug("Applying profile '{}': valid user packs {}, built-in preserved {}", profile.name, validUserPacks, builtinPacks)

        client.options.resourcePacks.clear()
        client.options.resourcePacks.addAll(fullPackList)

        client.options.save()
        client.resourcePackRepository.reload()
        client.resourcePackRepository.setSelected(fullPackList)

        client.reloadResourcePacks()
        lastActiveProfileName = profile.name
        save()

        return missingIds
    }
}
