package org.zephbyte.resourcepackprofiles.client.profile

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deleting a pack from the GUI moves it into a trash folder in the game directory instead of
 * erasing it, so the delete can be undone. [empty] is the only thing that removes files for good.
 */
object PackTrash {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")
    private val packsDir: Path get() = FabricLoader.getInstance().gameDir.resolve("resourcepacks").normalize()
    private val trashDir: Path get() = FabricLoader.getInstance().gameDir.resolve("resourcepackprofiles-trash").normalize()

    /** A pack that was moved to the trash and can be put back with [restore]. */
    class Trashed(val packId: String, internal val original: Path, internal val trashed: Path)

    /** Moves the file or folder behind [packId] (a `file/<name>` id) into the trash. */
    fun trash(packId: String): Trashed {
        if (!packId.startsWith("file/")) throw IOException("Not a pack file: $packId")
        val source = packsDir.resolve(packId.removePrefix("file/")).normalize()
        if (source.parent != packsDir || !Files.exists(source)) throw IOException("Pack file not found: $packId")
        Files.createDirectories(trashDir)
        // Prefix with a timestamp so trashing two packs of the same name never collides.
        val target = trashDir.resolve("${System.currentTimeMillis()}-${source.fileName}")
        Files.move(source, target)
        logger.info("Moved pack {} to trash", source.fileName)
        return Trashed(packId, source, target)
    }

    /** Moves a trashed pack back. Returns false if it is gone or its old name is taken again. */
    fun restore(item: Trashed): Boolean {
        return try {
            if (!Files.exists(item.trashed) || Files.exists(item.original)) return false
            Files.move(item.trashed, item.original)
            true
        } catch (e: IOException) {
            logger.error("Failed to restore pack {}", item.packId, e)
            false
        }
    }

    /** Number of packs currently in the trash. */
    fun count(): Int {
        return try {
            if (!Files.isDirectory(trashDir)) return 0
            Files.list(trashDir).use { it.count().toInt() }
        } catch (e: IOException) {
            0
        }
    }

    /** Original file names of the packs in the trash, sorted. */
    fun list(): List<String> {
        return try {
            if (!Files.isDirectory(trashDir)) return emptyList()
            Files.list(trashDir).use { stream ->
                // Strip the "<timestamp>-" prefix that [trash] adds to keep names unique.
                stream.map { it.fileName.toString().replaceFirst(TIMESTAMP_PREFIX, "") }.toList()
            }.sortedBy { it.lowercase() }
        } catch (e: IOException) {
            emptyList()
        }
    }

    private val TIMESTAMP_PREFIX = Regex("^\\d+-")

    /** Permanently deletes everything in the trash. Returns how many packs were removed. */
    fun empty(): Int {
        if (!Files.isDirectory(trashDir)) return 0
        var removed = 0
        val entries = Files.list(trashDir).use { it.toList() }
        for (entry in entries) {
            try {
                // Deepest paths first, so folders are empty by the time they are deleted.
                Files.walk(entry).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
                removed++
            } catch (e: IOException) {
                logger.error("Failed to delete {} from trash", entry.fileName, e)
            }
        }
        return removed
    }
}
