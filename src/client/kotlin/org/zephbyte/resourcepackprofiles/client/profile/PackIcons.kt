package org.zephbyte.resourcepackprofiles.client.profile

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft

object PackIcons {

    /**
     * Reads a resource pack's `pack.png` as a [NativeImage], or null if the pack is unavailable or
     * has no icon. The caller owns the returned image and must close it.
     */
    fun read(packId: String): NativeImage? {
        val repo = Minecraft.getInstance().resourcePackRepository
        val packProfile = repo.availablePacks.find { it.id == packId } ?: return null
        return try {
            val supplier = packProfile.open().getRootResource("pack.png") ?: return null
            supplier.get().use { NativeImage.read(it) }
        } catch (_: Exception) {
            null
        }
    }
}
