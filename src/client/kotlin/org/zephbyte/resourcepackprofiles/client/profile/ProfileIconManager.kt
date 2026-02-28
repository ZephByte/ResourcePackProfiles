package org.zephbyte.resourcepackprofiles.client.profile

import net.minecraft.client.MinecraftClient
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.util.Identifier
import org.lwjgl.util.tinyfd.TinyFileDialogs
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object ProfileIconManager {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")
    private val iconsDir: Path = Path.of("config", "resourcepackprofiles", "icons")
    private val cache = mutableMapOf<String, Identifier>()
    private val registeredTextures = mutableSetOf<Identifier>()
    private const val ICON_SIZE = 64

    fun getIconId(profile: ResourcePackProfile): Identifier {
        cache[profile.name]?.let { return it }

        val image = loadIcon(profile)
        if (image != null) {
            val id = registerTexture(profile.name, image)
            cache[profile.name] = id
            return id
        }

        return Identifier.ofVanilla("textures/misc/unknown_pack.png")
    }

    private fun loadIcon(profile: ResourcePackProfile): NativeImage? {
        // Try custom icon first
        if (profile.customIcon != null) {
            val customPath = iconsDir.resolve(profile.customIcon)
            if (Files.exists(customPath)) {
                try {
                    val image = Files.newInputStream(customPath).use { NativeImage.read(it) }
                    return resizeImage(image, ICON_SIZE, ICON_SIZE)
                } catch (e: Exception) {
                    logger.error("Failed to load custom icon for '${profile.name}'", e)
                }
            }
        }

        // Auto-generate composite from pack icons
        return generateCompositeIcon(profile)
    }

    private fun generateCompositeIcon(profile: ResourcePackProfile): NativeImage? {
        val client = MinecraftClient.getInstance()
        val packManager = client.resourcePackManager

        val packIcons = mutableListOf<NativeImage>()
        try {
            for (packId in profile.packIds) {
                if (!packId.startsWith("file/")) continue
                val packProfile = packManager.profiles.find { it.id == packId } ?: continue
                try {
                    val pack = packProfile.createResourcePack() ?: continue
                    val iconSupplier = pack.openRoot("pack.png") ?: continue
                    val icon = iconSupplier.get().use { NativeImage.read(it) }
                    packIcons.add(icon)
                    if (packIcons.size >= 4) break
                } catch (e: Exception) {
                    logger.debug("Could not read pack.png for pack '{}'", packId)
                }
            }
        } catch (e: Exception) {
            logger.error("Error generating composite icon", e)
        }

        if (packIcons.isEmpty()) return null

        val composite = NativeImage(ICON_SIZE, ICON_SIZE, true)
        // Fill with transparent
        for (x in 0 until ICON_SIZE) {
            for (y in 0 until ICON_SIZE) {
                composite.setColorArgb(x, y, 0)
            }
        }

        val half = ICON_SIZE / 2
        when (packIcons.size) {
            1 -> drawScaled(composite, packIcons[0], 0, 0, ICON_SIZE, ICON_SIZE)
            2 -> {
                drawScaled(composite, packIcons[0], 0, 0, half, half)
                drawScaled(composite, packIcons[1], half, 0, half, half)
            }
            3 -> {
                drawScaled(composite, packIcons[0], 0, 0, half, half)
                drawScaled(composite, packIcons[1], half, 0, half, half)
                drawScaled(composite, packIcons[2], 0, half, half, half)
            }
            else -> {
                drawScaled(composite, packIcons[0], 0, 0, half, half)
                drawScaled(composite, packIcons[1], half, 0, half, half)
                drawScaled(composite, packIcons[2], 0, half, half, half)
                drawScaled(composite, packIcons[3], half, half, half, half)
            }
        }

        // Close source images
        packIcons.forEach { it.close() }

        return composite
    }

    private fun drawScaled(dest: NativeImage, src: NativeImage, destX: Int, destY: Int, destW: Int, destH: Int) {
        val srcW = src.width
        val srcH = src.height
        for (x in 0 until destW) {
            for (y in 0 until destH) {
                val srcXi = (x * srcW / destW).coerceIn(0, srcW - 1)
                val srcYi = (y * srcH / destH).coerceIn(0, srcH - 1)
                dest.setColorArgb(destX + x, destY + y, src.getColorArgb(srcXi, srcYi))
            }
        }
    }

    private fun resizeImage(src: NativeImage, targetW: Int, targetH: Int): NativeImage {
        if (src.width == targetW && src.height == targetH) return src
        val resized = NativeImage(targetW, targetH, true)
        drawScaled(resized, src, 0, 0, targetW, targetH)
        src.close()
        return resized
    }

    private fun registerTexture(profileName: String, image: NativeImage): Identifier {
        val client = MinecraftClient.getInstance()
        val sanitized = profileName.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        val id = Identifier.of("resourcepackprofiles", "profile_icon/$sanitized")

        // Destroy old texture if it exists
        if (id in registeredTextures) {
            client.textureManager.destroyTexture(id)
        }

        val texture = NativeImageBackedTexture({ "resourcepackprofiles/profile_icon/$sanitized" }, image)
        client.textureManager.registerTexture(id, texture)
        registeredTextures.add(id)
        return id
    }

    fun invalidate(profileName: String) {
        val id = cache.remove(profileName) ?: return
        val client = MinecraftClient.getInstance()
        client.textureManager.destroyTexture(id)
        registeredTextures.remove(id)
    }

    fun cleanup() {
        val client = MinecraftClient.getInstance()
        for (id in registeredTextures) {
            client.textureManager.destroyTexture(id)
        }
        registeredTextures.clear()
        cache.clear()
    }

    fun importCustomIcon(profileName: String, sourcePath: Path) {
        Files.createDirectories(iconsDir)
        val fileName = "${profileName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")}.png"
        val destPath = iconsDir.resolve(fileName)

        // Read, resize to 64x64, save as PNG
        val image = Files.newInputStream(sourcePath).use { NativeImage.read(it) }
        val resized = resizeImage(image, ICON_SIZE, ICON_SIZE)
        resized.writeTo(destPath)
        resized.close()

        ProfileManager.setCustomIcon(profileName, fileName)
        // Defer texture invalidation to the render thread
        MinecraftClient.getInstance().execute { invalidate(profileName) }
    }

    fun openFilePickerAndImport(profileName: String): Boolean {
        val path = TinyFileDialogs.tinyfd_openFileDialog(
            "Select Profile Icon",
            null,
            arrayOf("*.png", "*.jpg", "*.jpeg").toFilterBuffer(),
            "Image Files (*.png, *.jpg)",
            false
        ) ?: return false

        try {
            importCustomIcon(profileName, Path.of(path))
            return true
        } catch (e: Exception) {
            logger.error("Failed to import custom icon", e)
            return false
        }
    }

    fun deleteCustomIcon(profile: ResourcePackProfile) {
        if (profile.customIcon != null) {
            val iconPath = iconsDir.resolve(profile.customIcon)
            try {
                Files.deleteIfExists(iconPath)
            } catch (e: Exception) {
                logger.error("Failed to delete custom icon file", e)
            }
        }
        invalidate(profile.name)
    }

    private fun Array<String>.toFilterBuffer(): org.lwjgl.PointerBuffer {
        val buf = org.lwjgl.BufferUtils.createPointerBuffer(this.size)
        for (pattern in this) {
            buf.put(org.lwjgl.system.MemoryUtil.memUTF8(pattern))
        }
        buf.flip()
        return buf
    }
}
