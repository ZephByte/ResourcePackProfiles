package org.zephbyte.resourcepackprofiles.client.util

import com.mojang.blaze3d.platform.NativeImage
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.update.ModrinthApi
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry

/** Image decoding shared by the mod: anything Java can read, plus WebP. */
object ImageDecoding {
    // Registered by hand: ImageIO only discovers plugins on the system class path at startup, and
    // the bundled WebP reader lives inside the mod jar.
    private val webpRegistered: Unit by lazy {
        IIORegistry.getDefaultInstance().registerServiceProvider(WebPImageReaderSpi())
    }

    fun ensureWebp() = webpRegistered

    /**
     * Decodes [bytes] (PNG, JPEG, GIF, BMP or WebP) into [size] x [size] ARGB pixels, centre-cropped
     * to a square and sampled directly, so a large image costs no more than a small one. Returns
     * null if the image can't be read.
     */
    fun decodeSquare(bytes: ByteArray, size: Int): IntArray? {
        ensureWebp()
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val side = minOf(source.width, source.height)
        if (side <= 0) return null
        val offsetX = (source.width - side) / 2
        val offsetY = (source.height - side) / 2
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = source.getRGB(offsetX + x * side / size, offsetY + y * side / size)
            }
        }
        return pixels
    }
}

/**
 * Loads images from URLs (Modrinth project icons) in the background and turns them into GUI
 * textures. [get] is called every frame from the render thread: it returns the texture once ready
 * and null meanwhile, so callers draw a placeholder. Call [cleanup] when the screen closes.
 */
class RemoteImages(private val pathPrefix: String, private val size: Int) {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    // Decoded but not yet uploaded pixels, and URLs that failed (not retried this session).
    private val decoded = ConcurrentHashMap<String, IntArray>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val textures = DynamicTextureCache(pathPrefix)
    private val ready = mutableMapOf<String, Identifier>()

    fun get(url: String?): Identifier? {
        if (url.isNullOrBlank() || url in failed) return null
        ready[url]?.let { return it }
        val pixels = decoded.remove(url)
        if (pixels != null) {
            // Textures must be created on the render thread, which is where this runs.
            val image = NativeImage(size, size, true)
            for (y in 0 until size) for (x in 0 until size) image.setPixel(x, y, pixels[y * size + x])
            val id = textures.getOrRegister(url, FALLBACK) { image }
            ready[url] = id
            return id
        }
        if (requested.add(url)) {
            EXECUTOR.execute {
                try {
                    val bytes = ModrinthApi.fetchImage(url)
                    val result = ImageDecoding.decodeSquare(bytes, size)
                    if (result == null) failed.add(url) else decoded[url] = result
                } catch (e: Exception) {
                    logger.debug("Could not load image {}: {}", url, e.message)
                    failed.add(url)
                }
            }
        }
        return null
    }

    fun cleanup() {
        textures.cleanup()
        ready.clear()
        decoded.clear()
        requested.clear()
    }

    private companion object {
        val FALLBACK: Identifier = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png")

        // A few threads, so a page of icons arrives quickly without flooding the CDN.
        val EXECUTOR = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "RPP-Images").apply { isDaemon = true }
        }
    }
}
