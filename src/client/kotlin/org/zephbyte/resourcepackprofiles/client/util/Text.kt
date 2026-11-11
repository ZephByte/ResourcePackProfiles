package org.zephbyte.resourcepackprofiles.client.util

import net.minecraft.network.chat.Component

/** Shorthand for a translated text component, used throughout the mod's screens. */
fun t(key: String, vararg args: Any): Component = Component.translatable(key, *args)
