package org.zephbyte.resourcepackprofiles.client.update

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/** A newer file available for an installed pack. */
data class PackUpdate(
    /** Id of the installed pack, e.g. `file/Foo-1.0.zip`. */
    val packId: String,
    val projectTitle: String,
    val currentVersion: String,
    val newVersion: String,
    val fileName: String,
    val url: String,
    val sha512: String,
    /** Modrinth version id of the file this downloads. */
    val versionId: String,
    /** Modrinth project the version belongs to, so the pack can be found again later. */
    val projectId: String = "",
    /** File size in bytes, for the progress bar; 0 when unknown. */
    val size: Long = 0
)

/** Which Modrinth version, and project, an installed pack file is. */
class PackIdentity(val versionId: String, val projectId: String)

/**
 * Result of [UpdateChecker.check]. [identities] maps each pack that Modrinth recognised to the
 * version id of the file currently installed.
 */
class CheckReport(
    val checks: Map<String, PackCheck>,
    val identities: Map<String, String>,
    /** Pack id -> Modrinth project id, for the packs in [identities]. */
    val projects: Map<String, String>
)

/**
 * One version of a pack that the user can switch to. [update] carries what to download;
 * [compatible] is whether it supports the running game version; [current] marks the version
 * that is installed right now.
 */
class VersionChoice(
    val update: PackUpdate,
    val compatible: Boolean,
    val current: Boolean,
    /** The game versions this version supports, oldest first. */
    val gameVersions: List<String> = emptyList()
)

/** Result of checking one installed pack. */
sealed interface PackCheck {
    data class UpdateAvailable(val update: PackUpdate) : PackCheck
    data object UpToDate : PackCheck
    /** Known to Modrinth, but no version supports the running Minecraft version. */
    data object NoCompatibleVersion : PackCheck
    data object NotOnModrinth : PackCheck
    /** Not a single resource pack file (folder pack, built-in, or file is gone). */
    data object Unavailable : PackCheck
}

object UpdateChecker {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")
    private val packsDir: Path get() = FabricLoader.getInstance().gameDir.resolve("resourcepacks").normalize()

    /** The running Minecraft version, as Modrinth spells it (e.g. "26.3"). */
    fun gameVersion(): String =
        FabricLoader.getInstance().getModContainer("minecraft").get().metadata.version.friendlyString

    /** Resolves a `file/<name>` pack id to its zip in the resourcepacks folder, or null. */
    fun packFile(packId: String): Path? {
        if (!packId.startsWith("file/")) return null
        val path = packsDir.resolve(packId.removePrefix("file/")).normalize()
        if (path.parent != packsDir) return null
        return path.takeIf { Files.isRegularFile(it) }
    }

    /** Checks each pack in [packIds] against Modrinth. Blocking; call off the render thread. */
    fun check(packIds: Collection<String>): CheckReport {
        val results = mutableMapOf<String, PackCheck>()
        val identities = mutableMapOf<String, String>()
        val projects = mutableMapOf<String, String>()
        val hashById = mutableMapOf<String, String>()
        for (id in packIds.distinct()) {
            val file = packFile(id)
            if (file == null) {
                results[id] = PackCheck.Unavailable
                continue
            }
            try {
                hashById[id] = hash(file, "SHA-1")
            } catch (e: IOException) {
                logger.warn("Could not hash pack {}", id, e)
                results[id] = PackCheck.Unavailable
            }
        }
        if (hashById.isEmpty()) return CheckReport(results, identities, projects)

        val hashes = hashById.values.toSet()
        val known = ModrinthApi.versionsByHash(hashes)
        val latest = if (known.isEmpty()) emptyMap() else ModrinthApi.latestByHash(known.keys, gameVersion())
        val titles = ModrinthApi.projectTitles(known.values.map { it.projectId })

        for ((id, hash) in hashById) {
            val current = known[hash]
            if (current == null) {
                results[id] = PackCheck.NotOnModrinth
                continue
            }
            identities[id] = current.id
            projects[id] = current.projectId
            val newest = latest[hash]
            if (newest == null) {
                results[id] = PackCheck.NoCompatibleVersion
                continue
            }
            // Only offer strictly newer versions: a pack already newer than the latest one
            // compatible with this Minecraft version must not be "updated" backwards.
            val isNewer = newest.id != current.id && newest.datePublished > current.datePublished
            val file = newest.files.firstOrNull { it.primary } ?: newest.files.firstOrNull()
            val sha512 = file?.hashes?.get("sha512")
            if (!isNewer || file == null || sha512 == null) {
                results[id] = PackCheck.UpToDate
                continue
            }
            results[id] = PackCheck.UpdateAvailable(
                PackUpdate(
                    packId = id,
                    projectTitle = titles[current.projectId] ?: current.name,
                    currentVersion = current.versionNumber,
                    newVersion = newest.versionNumber,
                    fileName = file.filename,
                    url = file.url,
                    sha512 = sha512.lowercase(),
                    versionId = newest.id,
                    projectId = current.projectId,
                    size = file.size
                )
            )
        }
        return CheckReport(results, identities, projects)
    }

    /**
     * Looks up which Modrinth version each installed pack in [packIds] is, without checking for
     * updates. Packs that are not files or not on Modrinth are absent. Blocking.
     */
    fun identify(packIds: Collection<String>): Map<String, PackIdentity> {
        val hashById = mutableMapOf<String, String>()
        for (id in packIds.distinct()) {
            val file = packFile(id) ?: continue
            try {
                hashById[id] = hash(file, "SHA-1")
            } catch (e: IOException) {
                logger.warn("Could not hash pack {}", id, e)
            }
        }
        val known = ModrinthApi.versionsByHash(hashById.values.toSet())
        return hashById.mapNotNull { (id, hash) -> known[hash]?.let { id to PackIdentity(it.id, it.projectId) } }.toMap()
    }

    /**
     * Lists every version of a pack, newest first, or null if the pack can't be tied to a Modrinth
     * project. The project comes from [projectId] if known; otherwise from [knownVersionId] (the
     * version recorded for the pack), otherwise from the installed file's hash. A pack whose file
     * or recorded version no longer exists can still be listed through [projectId]. Blocking.
     */
    fun listVersions(packId: String, knownVersionId: String?, projectId: String?): List<VersionChoice>? {
        var current: ApiVersion? = knownVersionId?.let { ModrinthApi.versionsByIds(listOf(it)).firstOrNull() }
        if (current == null && projectId == null && packFile(packId) != null) {
            val id = identify(listOf(packId))[packId]?.versionId
            if (id != null) current = ModrinthApi.versionsByIds(listOf(id)).firstOrNull()
        }
        val project = projectId ?: current?.projectId ?: return null
        val title = ModrinthApi.projectTitles(listOf(project))[project] ?: current?.name ?: ""
        val game = gameVersion()
        return ModrinthApi.projectVersions(project)
            .sortedByDescending { it.datePublished }
            .mapNotNull { version ->
                val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull() ?: return@mapNotNull null
                val sha512 = file.hashes["sha512"] ?: return@mapNotNull null
                VersionChoice(
                    PackUpdate(
                        packId = packId,
                        projectTitle = title,
                        currentVersion = current?.versionNumber ?: "",
                        newVersion = version.versionNumber,
                        fileName = file.filename,
                        url = file.url,
                        sha512 = sha512.lowercase(),
                        versionId = version.id,
                        projectId = version.projectId,
                        size = file.size
                    ),
                    compatible = game in version.gameVersions,
                    current = version.id == current?.id
                )
            }
    }

    /**
     * Every version of a project, newest first, ready to install. Nothing is installed from it
     * yet, so the pack id is empty and no version counts as current. Blocking.
     */
    fun projectVersions(projectId: String, title: String): List<VersionChoice> {
        val game = gameVersion()
        return ModrinthApi.projectVersions(projectId)
            .sortedByDescending { it.datePublished }
            .mapNotNull { version ->
                val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull() ?: return@mapNotNull null
                val sha512 = file.hashes["sha512"] ?: return@mapNotNull null
                VersionChoice(
                    PackUpdate(
                        packId = "",
                        projectTitle = title,
                        currentVersion = "",
                        newVersion = version.versionNumber,
                        fileName = file.filename,
                        url = file.url,
                        sha512 = sha512.lowercase(),
                        versionId = version.id,
                        projectId = version.projectId,
                        size = file.size
                    ),
                    compatible = game in version.gameVersions,
                    current = false,
                    gameVersions = version.gameVersions
                )
            }
    }

    /**
     * Searches Modrinth for the pack behind [packId] by a name guessed from its file name. For
     * packs that cannot be identified any other way. Blocking.
     */
    fun searchProjects(packId: String): List<ProjectHit> = ModrinthApi.searchProjects(guessName(packId))

    /** "Fancy_Beds-v4.1.zip" -> "Fancy Beds": the file name without extension, version and separators. */
    fun guessName(packId: String): String {
        val base = packId.removePrefix("file/").replace(Regex("\\.zip$", RegexOption.IGNORE_CASE), "")
        // Cut at the first separator that is followed by a version-looking token.
        val cut = Regex("[\\s_-]+v?\\d").find(base)?.range?.first?.takeIf { it > 0 } ?: base.length
        val name = base.substring(0, cut).replace(Regex("[_-]+"), " ").replace(Regex("\\s+"), " ").trim()
        return name.ifEmpty { base }
    }

    /**
     * Resolves missing packs to downloads. [sources] maps each missing pack id to the Modrinth
     * version id recorded for it; packs whose version no longer exists are absent. Blocking.
     */
    fun resolveMissing(sources: Map<String, String>): List<PackUpdate> {
        val versions = ModrinthApi.versionsByIds(sources.values).associateBy { it.id }
        return sources.mapNotNull { (packId, versionId) ->
            val version = versions[versionId] ?: return@mapNotNull null
            val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull() ?: return@mapNotNull null
            val sha512 = file.hashes["sha512"] ?: return@mapNotNull null
            PackUpdate(
                packId = packId,
                projectTitle = version.name,
                currentVersion = version.versionNumber,
                newVersion = version.versionNumber,
                fileName = file.filename,
                url = file.url,
                sha512 = sha512.lowercase(),
                versionId = version.id,
                projectId = version.projectId,
                size = file.size
            )
        }
    }

    /**
     * Downloads [update] into the resourcepacks folder and returns the new pack id. Never
     * overwrites an existing file: a same-named file with identical content is reused, a
     * different one gets a numeric suffix. Blocking; call off the render thread.
     */
    fun download(update: PackUpdate, onBytes: (Long) -> Unit = {}): String {
        val uri = URI.create(update.url)
        val host = uri.host ?: throw IOException("Download URL has no host")
        if (uri.scheme != "https" || !(host == "modrinth.com" || host.endsWith(".modrinth.com"))) {
            throw IOException("Refusing to download from $host")
        }
        val name = update.fileName
        if (name.isBlank() || name != Path.of(name).fileName.toString() || !name.lowercase().endsWith(".zip")) {
            throw IOException("Unsafe file name: $name")
        }

        Files.createDirectories(packsDir)
        val stem = name.dropLast(4)
        var target = packsDir.resolve(name)
        var counter = 1
        while (Files.exists(target)) {
            if (hash(target, "SHA-512") == update.sha512) return "file/${target.fileName}"
            target = packsDir.resolve("$stem (${counter++}).zip")
        }

        // Download beside the target under a non-.zip name so the game never scans a partial file.
        val partial = target.resolveSibling("${target.fileName}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-512")
            ModrinthApi.openStream(update.url).use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    Files.newOutputStream(partial).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            onBytes(read.toLong())
                        }
                    }
                }
            }
            if (toHex(digest.digest()) != update.sha512) {
                throw IOException("Checksum mismatch for $name")
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial)
        }
        logger.info("Downloaded pack update {}", target.fileName)
        return "file/${target.fileName}"
    }

    /**
     * Downloads [updates] one after another, keeping [progress] current. A download that fails is
     * recorded and skipped, so one bad file doesn't stop the rest. Blocking.
     */
    fun downloadAll(updates: List<PackUpdate>, progress: DownloadProgress): DownloadOutcome {
        val replacements = mutableMapOf<String, String>()
        val failed = mutableListOf<PackUpdate>()
        updates.forEachIndexed { i, update ->
            val item = progress.items[i]
            item.state = DownloadProgress.State.ACTIVE
            try {
                val id = download(update) { item.bytes += it }
                replacements[update.packId] = id
                item.resultId = id
                item.state = DownloadProgress.State.DONE
            } catch (e: Exception) {
                logger.warn("Could not download {}: {}", update.fileName, e.message)
                item.state = DownloadProgress.State.FAILED
                failed.add(update)
            }
        }
        return DownloadOutcome(replacements, failed)
    }

    /**
     * Copies a resource pack zip dropped onto the GUI into the resourcepacks folder. Returns the
     * installed file name, or null if [source] is not a zip or a file of that name already exists.
     */
    fun installPack(source: Path): String? {
        val name = source.fileName?.toString() ?: return null
        if (!name.lowercase().endsWith(".zip") || !Files.isRegularFile(source)) return null
        Files.createDirectories(packsDir)
        val target = packsDir.resolve(name)
        if (Files.exists(target)) return null
        Files.copy(source, target)
        return name
    }

    private fun hash(file: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
