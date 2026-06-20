package org.zephbyte.resourcepackprofiles.client.util

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * Caches [NativeImage]s registered as dynamic GUI textures under a namespace path [pathPrefix],
 * keyed by an arbitrary string. Handles sanitizing keys into valid identifier paths, replacing an
 * existing texture for a key, and releasing GPU textures on invalidate/cleanup so callers don't
 * leak them.
 */
class DynamicTextureCache(private val pathPrefix: String) {

    private val cache = mutableMapOf<String, Identifier>()
    private val registered = mutableSetOf<Identifier>()

    /**
     * Returns the cached texture id for [key], registering one from [image] on a cache miss.
     * [image] is only invoked on a miss; if it returns null, [fallback] is returned and nothing
     * is cached (so a later call will retry).
     */
    fun getOrRegister(key: String, fallback: Identifier, image: () -> NativeImage?): Identifier {
        cache[key]?.let { return it }
        val img = image() ?: return fallback
        val id = register(key, img)
        cache[key] = id
        return id
    }

    private fun register(key: String, image: NativeImage): Identifier {
        val client = Minecraft.getInstance()
        val sanitized = key.lowercase().replace(Regex("[^a-z0-9_.\\-/]"), "_")
        val path = "$pathPrefix/$sanitized"
        val id = Identifier.fromNamespaceAndPath("resourcepackprofiles", path)
        if (id in registered) client.textureManager.release(id)
        client.textureManager.register(id, DynamicTexture({ "resourcepackprofiles/$path" }, image))
        registered.add(id)
        return id
    }

    /** Releases the texture for [key] (if any) so it will be regenerated on next access. */
    fun invalidate(key: String) {
        val id = cache.remove(key) ?: return
        Minecraft.getInstance().textureManager.release(id)
        registered.remove(id)
    }

    /** Releases every texture this cache has registered. */
    fun cleanup() {
        val client = Minecraft.getInstance()
        registered.forEach { client.textureManager.release(it) }
        registered.clear()
        cache.clear()
    }
}
