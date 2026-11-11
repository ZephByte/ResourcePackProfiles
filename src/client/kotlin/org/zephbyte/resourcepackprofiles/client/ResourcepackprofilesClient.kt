package org.zephbyte.resourcepackprofiles.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import org.zephbyte.resourcepackprofiles.client.profile.AutoApply
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager

class ResourcepackprofilesClient : ClientModInitializer {

    override fun onInitializeClient() {
        ProfileManager.load()

        // Deferred to the next client tick so the level is fully set up before packs reload.
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            client.execute { AutoApply.onJoin(client) }
        }
    }
}
