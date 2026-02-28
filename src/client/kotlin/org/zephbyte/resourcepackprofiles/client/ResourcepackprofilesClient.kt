package org.zephbyte.resourcepackprofiles.client

import net.fabricmc.api.ClientModInitializer
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager

class ResourcepackprofilesClient : ClientModInitializer {

    override fun onInitializeClient() {
        ProfileManager.load()
    }
}
