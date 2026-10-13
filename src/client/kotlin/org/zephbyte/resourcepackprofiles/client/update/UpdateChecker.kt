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
    val sha512: String
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

    private fun gameVersion(): String =
        FabricLoader.getInstance().getModContainer("minecraft").get().metadata.version.friendlyString

    /** Resolves a `file/<name>` pack id to its zip in the resourcepacks folder, or null. */
    private fun packFile(packId: String): Path? {
        if (!packId.startsWith("file/")) return null
        val path = packsDir.resolve(packId.removePrefix("file/")).normalize()
        if (path.parent != packsDir) return null
        return path.takeIf { Files.isRegularFile(it) }
    }

    /** Checks each pack in [packIds] against Modrinth. Blocking; call off the render thread. */
    fun check(packIds: Collection<String>): Map<String, PackCheck> {
        val results = mutableMapOf<String, PackCheck>()
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
        if (hashById.isEmpty()) return results

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
                    sha512 = sha512.lowercase()
                )
            )
        }
        return results
    }

    /**
     * Downloads [update] into the resourcepacks folder and returns the new pack id. Never
     * overwrites an existing file: a same-named file with identical content is reused, a
     * different one gets a numeric suffix. Blocking; call off the render thread.
     */
    fun download(update: PackUpdate): String {
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
                DigestInputStream(raw, digest).use { Files.copy(it, partial, StandardCopyOption.REPLACE_EXISTING) }
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
