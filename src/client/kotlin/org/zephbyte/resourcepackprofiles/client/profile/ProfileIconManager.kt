package org.zephbyte.resourcepackprofiles.client.profile

import net.minecraft.client.Minecraft
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.util.DynamicTextureCache
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

object ProfileIconManager {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")
    private val iconsDir: Path = Path.of("config", "resourcepackprofiles", "icons")
    private val textures = DynamicTextureCache("profile_icon")
    private const val ICON_SIZE = 64
    private val UNKNOWN_PACK = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png")

    fun getIconId(profile: ResourcePackProfile): Identifier =
        textures.getOrRegister(profile.name, UNKNOWN_PACK) { loadIcon(profile) }

    private fun loadIcon(profile: ResourcePackProfile): NativeImage? {
        // Try custom icon first
        if (profile.customIcon != null) {
            val customPath = iconsDir.resolve(profile.customIcon)
            if (Files.exists(customPath)) {
                try {
                    val image = Files.newInputStream(customPath).use { NativeImage.read(it) }
                    return resizeToIcon(image)
                } catch (e: Exception) {
                    logger.error("Failed to load custom icon for '${profile.name}'", e)
                }
            }
        }

        // Auto-generate composite from pack icons
        return generateCompositeIcon(profile)
    }

    private fun generateCompositeIcon(profile: ResourcePackProfile): NativeImage? {
        val packIcons = mutableListOf<NativeImage>()
        try {
            for (packId in profile.packIds) {
                if (!packId.startsWith("file/")) continue
                val icon = PackIcons.read(packId) ?: continue
                packIcons.add(icon)
                if (packIcons.size >= 4) break
            }
        } catch (e: Exception) {
            logger.error("Error generating composite icon", e)
        }

        if (packIcons.isEmpty()) return null

        val composite = NativeImage(ICON_SIZE, ICON_SIZE, true)
        // Fill with transparent
        for (x in 0 until ICON_SIZE) {
            for (y in 0 until ICON_SIZE) {
                composite.setPixel(x, y, 0)
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
                dest.setPixel(destX + x, destY + y, src.getPixel(srcXi, srcYi))
            }
        }
    }

    private fun resizeToIcon(src: NativeImage): NativeImage {
        if (src.width == ICON_SIZE && src.height == ICON_SIZE) return src
        val resized = NativeImage(ICON_SIZE, ICON_SIZE, true)
        drawScaled(resized, src, 0, 0, ICON_SIZE, ICON_SIZE)
        src.close()
        return resized
    }

    fun invalidate(profileName: String) = textures.invalidate(profileName)

    fun cleanup() = textures.cleanup()

    /**
     * Builds an auto-composite icon from the given packs, ignoring any custom icon. Used for
     * previews; the caller owns the returned image. Returns null if no pack icons are available.
     */
    fun buildCompositeImage(packIds: List<String>): NativeImage? =
        generateCompositeIcon(ResourcePackProfile("", packIds))

    fun importCustomIcon(profileName: String, sourcePath: Path) {
        Files.createDirectories(iconsDir)
        val fileName = "${sanitizeFileName(profileName)}.png"
        val destPath = iconsDir.resolve(fileName)

        // Read, resize to 64x64, save as PNG. try/finally ensures the NativeImage is
        // always closed even if writeToFile throws (e.g. disk full, permissions).
        val image = Files.newInputStream(sourcePath).use { NativeImage.read(it) }
        val resized = resizeToIcon(image)
        try {
            resized.writeToFile(destPath)
        } finally {
            resized.close()
        }

        ProfileManager.setCustomIcon(profileName, fileName)
        // Defer texture invalidation to the render thread
        Minecraft.getInstance().execute { invalidate(profileName) }
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

    fun encodeIconToBase64(profileName: String): String? {
        val profile = ProfileManager.getProfiles().find { it.name == profileName } ?: return null
        if (profile.customIcon == null) return null
        val iconPath = iconsDir.resolve(profile.customIcon)
        if (!Files.exists(iconPath)) return null
        return try {
            val bytes = Files.readAllBytes(iconPath)
            Base64.getEncoder().encodeToString(bytes)
        } catch (e: Exception) {
            logger.error("Failed to encode icon to base64 for '$profileName'", e)
            null
        }
    }

    fun importIconFromBase64(profileName: String, base64: String) {
        Files.createDirectories(iconsDir)
        val fileName = "${sanitizeFileName(profileName)}.png"
        val destPath = iconsDir.resolve(fileName)
        // Exceptions propagate to the caller (importProfileFromPath), which catches them and
        // returns ImportResult.Failed without calling save() — so the old icon is preserved.
        val bytes = Base64.getDecoder().decode(base64)
        Files.write(destPath, bytes)
        ProfileManager.setCustomIcon(profileName, fileName)
        Minecraft.getInstance().execute { invalidate(profileName) }
    }

    /**
     * Maps a profile name to a filesystem-safe icon file stem. A short hash of the full name is
     * appended so two names that sanitize to the same characters (e.g. "My Pack" and "My/Pack")
     * don't collide on the same icon file and overwrite each other.
     */
    fun sanitizeFileName(profileName: String): String {
        val safe = profileName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val hash = Integer.toHexString(profileName.hashCode())
        return "${safe}_$hash"
    }
}
