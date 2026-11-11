package org.zephbyte.resourcepackprofiles.client.profile

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

object PackIcons {

    /**
     * Reads a resource pack's `pack.png` as a [NativeImage], or null if the pack is unavailable or
     * has no icon. The caller owns the returned image and must close it.
     */
    /**
     * Reads `pack.png` straight from a pack zip (or folder), without the game having scanned it
     * yet. Lets a just-downloaded pack show its icon before the pack list is reloaded.
     */
    fun readFile(path: Path): NativeImage? = try {
        if (Files.isDirectory(path)) {
            val icon = path.resolve("pack.png")
            if (Files.isRegularFile(icon)) Files.newInputStream(icon).use(NativeImage::read) else null
        } else {
            ZipFile(path.toFile()).use { zip ->
                val entry = zip.getEntry("pack.png") ?: return null
                zip.getInputStream(entry).use(NativeImage::read)
            }
        }
    } catch (_: Exception) {
        null
    }

    fun read(packId: String): NativeImage? {
        val repo = Minecraft.getInstance().resourcePackRepository
        val packProfile = repo.availablePacks.find { it.id == packId } ?: return null
        return try {
//? if >=26.3 {
            packProfile.open().use { packResources ->
                val resources = packResources.findFirst().orElse(null) ?: return null
                resources.use {
                    val supplier = it.getRootResource("pack.png") ?: return null
                    supplier.get().use(NativeImage::read)
                }
            }
//?} else {
/*            val supplier = packProfile.open().getRootResource("pack.png") ?: return null
            supplier.get().use { NativeImage.read(it) }
*///?}
        } catch (_: Exception) {
            null
        }
    }
}
