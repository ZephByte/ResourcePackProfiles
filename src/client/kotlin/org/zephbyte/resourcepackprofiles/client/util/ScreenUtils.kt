package org.zephbyte.resourcepackprofiles.client.util

import net.minecraft.client.gui.Font

object ScreenUtils {

    /** Trims [text] with a trailing ellipsis so it fits within [maxWidth] pixels for [font]. */
    fun truncate(font: Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = font.width(ellipsis)
        if (maxWidth <= ellipsisWidth) return ellipsis
        return font.plainSubstrByWidth(text, maxWidth - ellipsisWidth) + ellipsis
    }
}
