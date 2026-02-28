package org.zephbyte.resourcepackprofiles.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import org.zephbyte.resourcepackprofiles.client.screen.ProfileScreen

class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> ProfileScreen(parent) }
    }
}
