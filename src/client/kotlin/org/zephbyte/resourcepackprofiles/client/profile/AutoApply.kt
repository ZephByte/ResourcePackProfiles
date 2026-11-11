package org.zephbyte.resourcepackprofiles.client.profile

import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * Optional automatic switching: a profile can be bound to a world or server and is applied when
 * the player joins it. Nothing is bound by default, so this does nothing until the user opts in.
 */
object AutoApply {
    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    /** The world or server the player is in. [key] identifies it in the config; [label] is for display. */
    class Context(val key: String, val label: String)

    /** The world or server currently joined, or null from the title screen. */
    fun currentContext(client: Minecraft): Context? {
        if (client.level == null) return null
        if (client.hasSingleplayerServer()) {
            val name = client.singleplayerServer?.worldData?.levelName ?: return null
            return Context("world:$name", name)
        }
        val address = client.currentServer?.ip ?: return null
        return Context("server:${address.lowercase()}", address)
    }

    /** Applies the profile bound to the world or server just joined, if there is one. */
    fun onJoin(client: Minecraft) {
        val context = currentContext(client) ?: return
        val profile = ProfileManager.autoApplyProfile(context.key) ?: return
        if (ProfileManager.isActiveProfile(profile)) return
        logger.info("Auto-applying profile '{}' for {}", profile.name, context.label)
        ProfileManager.applyProfile(profile)
    }
}
