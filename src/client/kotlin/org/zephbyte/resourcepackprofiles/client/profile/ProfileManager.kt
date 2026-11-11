package org.zephbyte.resourcepackprofiles.client.profile

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.update.PackIdentity
import org.zephbyte.resourcepackprofiles.client.update.UpdateChecker
import org.zephbyte.resourcepackprofiles.client.util.FileDialogs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private data class ConfigData(
    val profiles: List<ResourcePackProfile> = emptyList(),
    val lastActiveProfile: String? = null,
    /** Context key ("server:<address>" / "world:<name>") -> profile to apply on joining it. */
    val autoApply: Map<String, String>? = null,
    /** Whether the profile screen opens in place of the vanilla resource pack screen. Null = default (on). */
    val replaceVanillaScreen: Boolean? = null,
    /** "ask" (default), "always" or "never": whether updating several packs backs the profile up first. */
    val backupOnUpdateAll: String? = null,
    /** Whether packs are checked for updates automatically. Null until the user has been asked. */
    val autoCheckUpdates: Boolean? = null
)

private data class ExportData(
    val name: String,
    val packIds: List<String>,
    val favorite: Boolean = false,
    val customIcon: String? = null,
    val disabledPackIds: List<String>? = null,
    val packSources: Map<String, String>? = null,
    val packProjects: Map<String, String>? = null
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
    private val autoApply = mutableMapOf<String, String>()

    fun load() {
        profiles.clear()
        autoApply.clear()
        if (Files.exists(configPath)) {
            try {
                val json = Files.readString(configPath)
                val config: ConfigData = gson.fromJson(json, ConfigData::class.java)
                config.profiles.forEach { profiles[it.name] = it }
                lastActiveProfileName = config.lastActiveProfile
                config.autoApply?.let { autoApply.putAll(it) }
                ModSettings.load(config.replaceVanillaScreen, config.backupOnUpdateAll, config.autoCheckUpdates)
                logger.info("Loaded {} profile(s) from config", profiles.size)
            } catch (e: Exception) {
                logger.error("Failed to load profiles config", e)
                profiles.clear()
            }
        }
    }

    /** Writes the config file: the profiles, and the [ModSettings] stored alongside them. */
    internal fun save() {
        try {
            Files.createDirectories(configPath.parent)
            val config = ConfigData(
                profiles.values.toList(), lastActiveProfileName, autoApply.toMap().ifEmpty { null },
                ModSettings.replaceVanillaScreen, ModSettings.backupChoice.id, ModSettings.autoCheckUpdates
            )
            Files.writeString(configPath, gson.toJson(config))
        } catch (e: Exception) {
            logger.error("Failed to save profiles config", e)
        }
    }

    fun hasProfile(name: String): Boolean = name in profiles

    fun getProfile(name: String): ResourcePackProfile? = profiles[name]

    /** Name of the profile most recently applied or saved from the current packs, if any. */
    fun lastAppliedName(): String? = lastActiveProfileName

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

    /** Saves a copy of [name] as "<name><suffix>" (numbered if taken) and returns the new name. */
    fun duplicateProfile(name: String, suffix: String = " (copy)"): String? {
        val source = profiles[name] ?: return null
        val copyName = uniqueName(name, suffix)
        profiles[copyName] = source.copy(name = copyName, customIcon = copyIcon(source, copyName), favorite = false)
        save()
        ProfileIconManager.invalidate(copyName)
        return copyName
    }

    /** Saves the currently enabled packs as a new profile with a generated name and returns it. */
    fun createFromCurrent(): String {
        val name = uniqueName("New profile", "")
        saveCurrentAsProfile(name)
        return name
    }

    /** Custom icon file contents for [profile], or null if it has none. Used to undo a delete. */
    fun readCustomIcon(profile: ResourcePackProfile): ByteArray? {
        val file = profile.customIcon ?: return null
        return try {
            iconsDir.resolve(file).takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }
        } catch (e: Exception) {
            logger.error("Failed to read custom icon for '{}'", profile.name, e)
            null
        }
    }

    /** Puts back a deleted [profile], rewriting its custom icon from [iconBytes] if it had one. */
    fun restoreProfile(profile: ResourcePackProfile, iconBytes: ByteArray?) {
        if (profile.name in profiles) return
        var restored = profile
        if (profile.customIcon != null) {
            try {
                if (iconBytes == null) throw java.io.IOException("icon data unavailable")
                Files.createDirectories(iconsDir)
                Files.write(iconsDir.resolve(profile.customIcon), iconBytes)
            } catch (e: Exception) {
                logger.error("Failed to restore custom icon for '{}'", profile.name, e)
                restored = profile.copy(customIcon = null)
            }
        }
        profiles[profile.name] = restored
        save()
        ProfileIconManager.invalidate(profile.name)
    }

    /** "<base><suffix>", numbered if taken, trimmed to fit the 64-char profile name limit. */
    private fun uniqueName(base: String, suffix: String): String {
        val trimmed = base.take(64 - suffix.length - 4)
        var name = trimmed + suffix
        var counter = 2
        while (name in profiles) name = "$trimmed$suffix ${counter++}"
        return name
    }

    /** Copies [source]'s custom icon file for a profile named [newName]; returns the new file name. */
    private fun copyIcon(source: ResourcePackProfile?, newName: String): String? {
        val sourceIcon = source?.customIcon ?: return null
        return try {
            val from = iconsDir.resolve(sourceIcon)
            if (!Files.exists(from)) return null
            val fileName = "${ProfileIconManager.sanitizeFileName(newName)}.png"
            Files.copy(from, iconsDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)
            fileName
        } catch (e: Exception) {
            logger.error("Failed to copy custom icon for profile '{}'", newName, e)
            null
        }
    }

    fun deleteProfile(name: String) {
        val profile = profiles.remove(name)
        if (profile != null) {
            ProfileIconManager.deleteCustomIcon(profile)
        }
        if (lastActiveProfileName == name) lastActiveProfileName = null
        autoApply.values.removeIf { it == name }
        save()
    }

    fun updateProfile(name: String, newPackIds: List<String>) {
        val profile = profiles[name] ?: return
        val disabled = profile.disabledPackIds?.filter { it in newPackIds }?.ifEmpty { null }
        profiles[name] = profile.copy(packIds = newPackIds, disabledPackIds = disabled)
        save()
        ProfileIconManager.invalidate(name)
    }

    /** Overwrites the stored profile of the same name with [profile]. Used to undo an edit. */
    fun putProfile(profile: ResourcePackProfile) {
        if (profile.name !in profiles) return
        profiles[profile.name] = profile
        save()
        ProfileIconManager.invalidate(profile.name)
    }

    /** Turns packs off (or back on) inside a profile without removing them from the order. */
    fun setPacksDisabled(name: String, packIds: Collection<String>, disabled: Boolean) {
        val profile = profiles[name] ?: return
        val current = profile.disabledPackIds.orEmpty()
        val updated = if (disabled) (current + packIds).distinct() else current - packIds.toSet()
        profiles[name] = profile.copy(disabledPackIds = updated.ifEmpty { null })
        save()
    }

    /**
     * Swaps pack ids in one profile in place, keeping order and disabled state. [newSources] maps
     * the new ids to their Modrinth version ids. With [rememberPrevious], each new pack records the
     * one it replaced so the switch can be reverted later.
     */
    fun replacePackIds(
        name: String,
        replacements: Map<String, String>,
        newSources: Map<String, String> = emptyMap(),
        newProjects: Map<String, String> = emptyMap(),
        rememberPrevious: Boolean = false,
        carrySources: Boolean = true
    ) {
        val profile = profiles[name] ?: return
        val newIds = profile.packIds.map { replacements[it] ?: it }.distinct()
        // A missing pack re-downloaded under another file name is still the same version, so its
        // recorded source moves to the new id ([carrySources]). A version switch is a different
        // version: the old source is dropped and [newSources] supplies the new one, if known.
        val carried =
            if (carrySources) profile.packSources.orEmpty().mapKeys { replacements[it.key] ?: it.key }
            else profile.packSources.orEmpty() - replacements.keys
        val sources = (carried + newSources).filterKeys { it in newIds }
        // The project is the same whichever version the pack is on, so it always follows the pack.
        val projects = (profile.packProjects.orEmpty().mapKeys { replacements[it.key] ?: it.key } + newProjects)
            .filterKeys { it in newIds }
        var previous = profile.previousPacks.orEmpty()
        if (rememberPrevious) previous = previous + replacements.map { (old, new) -> new to old }
        previous = previous.filterKeys { it in newIds }
        profiles[name] = profile.copy(
            packIds = newIds,
            disabledPackIds = profile.disabledPackIds?.map { replacements[it] ?: it }?.distinct(),
            packSources = sources.ifEmpty { null },
            packProjects = projects.ifEmpty { null },
            previousPacks = previous.ifEmpty { null }
        )
        save()
        ProfileIconManager.invalidate(name)
    }

    /**
     * Remembers which Modrinth version and project each pack in [sources] and [projects] is, on
     * every profile containing it, so the pack can be downloaded again if its file goes missing or
     * the profile is shared.
     */
    fun recordSources(sources: Map<String, String>, projects: Map<String, String> = emptyMap()) {
        if (sources.isEmpty() && projects.isEmpty()) return
        var changed = false
        for ((name, profile) in profiles.toMap()) {
            val mergedSources = profile.packSources.orEmpty() + sources.filterKeys { it in profile.packIds }
            val mergedProjects = profile.packProjects.orEmpty() + projects.filterKeys { it in profile.packIds }
            if (mergedSources != profile.packSources.orEmpty() || mergedProjects != profile.packProjects.orEmpty()) {
                profiles[name] = profile.copy(
                    packSources = mergedSources.ifEmpty { null },
                    packProjects = mergedProjects.ifEmpty { null }
                )
                changed = true
            }
        }
        if (changed) save()
    }

    /**
     * Forgets the recorded versions of [packIds] in one profile, for versions Modrinth no longer
     * has. The project stays, so another version can still be picked. This also stops the profile
     * offering to download a pack that can't be downloaded.
     */
    fun dropSources(name: String, packIds: Collection<String>) {
        val profile = profiles[name] ?: return
        val remaining = profile.packSources.orEmpty() - packIds.toSet()
        if (remaining == profile.packSources.orEmpty()) return
        profiles[name] = profile.copy(packSources = remaining.ifEmpty { null })
        save()
    }

    /** How many profiles contain each pack id. */
    fun packUsage(): Map<String, Int> =
        profiles.values.flatMap { it.packIds.distinct() }.groupingBy { it }.eachCount()

    // --- Automatic switching (opt-in: nothing happens until a binding is added) ---

    fun autoApplyProfile(contextKey: String): ResourcePackProfile? = autoApply[contextKey]?.let { profiles[it] }

    /** Binds [contextKey] to [profileName], or removes the binding when [profileName] is null. */
    fun setAutoApply(contextKey: String, profileName: String?) {
        if (profileName == null) autoApply.remove(contextKey) else autoApply[contextKey] = profileName
        save()
    }

    /** Context keys that auto-apply [profileName]. */
    fun autoApplyContexts(profileName: String): List<String> =
        autoApply.filterValues { it == profileName }.keys.sorted()

    fun clearAutoApply(profileName: String) {
        if (autoApply.values.removeIf { it == profileName }) save()
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
        autoApply.replaceAll { _, bound -> if (bound == oldName) newName else bound }
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
        val profileValidPacks = getUserPacks(profile.enabledPackIds()).filter { it in availableIds }
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
            val found = identifyForExport(profile)
            val sources = profile.packSources.orEmpty() + found.mapValues { it.value.versionId }
            val projects = profile.packProjects.orEmpty() + found.mapValues { it.value.projectId }
            val exportData = ExportData(
                name = profile.name,
                packIds = profile.packIds,
                favorite = profile.favorite,
                customIcon = iconBase64,
                disabledPackIds = profile.disabledPackIds,
                packSources = sources.ifEmpty { null },
                packProjects = projects.ifEmpty { null }
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
     * Asks Modrinth which version each not-yet-identified pack is, so the exported file lets the
     * recipient download packs they don't have. Best effort: export still works offline.
     */
    private fun identifyForExport(profile: ResourcePackProfile): Map<String, PackIdentity> {
        val unknown = profile.packIds.filter { profile.packSources?.containsKey(it) != true || profile.packProjects?.containsKey(it) != true }
        if (unknown.isEmpty()) return emptyMap()
        return try {
            UpdateChecker.identify(unknown)
        } catch (e: Exception) {
            logger.warn("Could not look up pack sources for export: {}", e.message)
            emptyMap()
        }
    }

    /** Opens a file dialog and imports the selected profile. Blocks, so call off the render thread. */
    fun pickProfileToImport(): String? =
        FileDialogs.openFile("Import Profile", profileFilters, PROFILE_FILTER_DESC)

    /**
     * Imports a profile from [filePath]. If [overwriteName] is null and a profile with the file's
     * name already exists, returns [ImportResult.Conflict] without writing anything; pass the name
     * back as [overwriteName] to confirm the overwrite. With [renameOnConflict], a clashing import
     * is saved under a numbered name instead of reporting a conflict.
     */
    fun importProfileFromPath(filePath: Path, overwriteName: String? = null, renameOnConflict: Boolean = false): ImportResult {
        return try {
            val json = Files.readString(filePath)
            val data: ExportData? = gson.fromJson(json, ExportData::class.java)
            // Gson ignores Kotlin nullability: a file missing these fields gives nulls here.
            @Suppress("SENSELESS_COMPARISON")
            if (data == null || data.name == null || data.name.isBlank() || data.packIds == null) {
                logger.error("Failed to import profile: {} is not a profile file", filePath.fileName)
                return ImportResult.Failed
            }
            var name = overwriteName ?: data.name

            if (overwriteName == null && hasProfile(name)) {
                if (!renameOnConflict) return ImportResult.Conflict(name)
                name = uniqueName(name, "")
            }

            // Capture old icon filename before overwriting — used for cleanup after a successful
            // write so we never delete the old file before the new one is confirmed on disk.
            val oldCustomIcon: String? = if (overwriteName != null) profiles[name]?.customIcon else null

            profiles[name] = ResourcePackProfile(
                name = name,
                packIds = data.packIds,
                favorite = data.favorite,
                disabledPackIds = data.disabledPackIds?.filter { it in data.packIds }?.ifEmpty { null },
                packSources = data.packSources?.filterKeys { it in data.packIds }?.ifEmpty { null },
                packProjects = data.packProjects?.filterKeys { it in data.packIds }?.ifEmpty { null }
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

        // Disabled packs are skipped on purpose, so they don't count as missing either.
        val enabledIds = profile.enabledPackIds()
        val missingIds = enabledIds.filter { it !in availableIds }
        if (missingIds.isNotEmpty()) {
            logger.warn("Profile '{}' is missing packs: {}", profile.name, missingIds)
        }

        // Strip required/non-auto packs so builtinPacks + validUserPacks never contains duplicates
        // (an imported profile could legally include a required pack id in its list).
        val validUserPacks = getUserPacks(enabledIds.filter { it in availableIds })

        // Preserve built-in/required packs that are always present
        val builtinPacks = client.options.resourcePacks.filter { it !in getUserPacks(client.options.resourcePacks.toList()) }
        val fullPackList = builtinPacks + validUserPacks
        logger.debug("Applying profile '{}': valid user packs {}, built-in preserved {}", profile.name, validUserPacks, builtinPacks)

        // Select in the repository, then let vanilla's own updateResourcePacks write the options,
        // exactly as the vanilla pack screen does. Besides saving and reloading, it records
        // outdated packs in options.incompatibleResourcePacks; without that, Minecraft drops
        // every outdated pack from the selection at the next startup.
        client.resourcePackRepository.reload()
        client.resourcePackRepository.setSelected(fullPackList)
        client.options.updateResourcePacks(client.resourcePackRepository)
        lastActiveProfileName = profile.name
        save()

        return missingIds
    }
}
