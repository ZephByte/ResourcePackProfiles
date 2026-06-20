package org.zephbyte.resourcepackprofiles.client.util

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

object ScreenUtils {

    /** Trims [text] with a trailing ellipsis so it fits within [maxWidth] pixels for [font]. */
    fun truncate(font: Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && font.width("$s...") > maxWidth) {
            s = s.dropLast(1)
        }
        return "$s..."
    }

    /**
     * Draws a small "box with an arrow" glyph used on the import/export buttons, assuming a 20x20
     * button at ([bx], [by]). When [into] is true the arrow points down into the box (import);
     * otherwise it points up out of the box (export).
     */
    fun drawTransferIcon(context: GuiGraphicsExtractor, bx: Int, by: Int, into: Boolean) {
        val white = 0xFFFFFFFF.toInt()
        // Box: open-top rectangle (bottom + left + right sides)
        context.fill(bx + 4, by + 15, bx + 16, by + 16, white)  // bottom
        context.fill(bx + 4, by + 10, bx + 5, by + 16, white)   // left
        context.fill(bx + 15, by + 10, bx + 16, by + 16, white) // right
        if (into) {
            // Arrow shaft extends past the chevron to form a sharp point
            context.fill(bx + 9, by + 5, bx + 11, by + 14, white)
            // Chevron pointing down
            context.fill(bx + 7, by + 11, bx + 9, by + 12, white)   // left wing
            context.fill(bx + 11, by + 11, bx + 13, by + 12, white) // right wing
            context.fill(bx + 8, by + 12, bx + 9, by + 13, white)   // left tip
            context.fill(bx + 11, by + 12, bx + 12, by + 13, white) // right tip
        } else {
            // Arrow shaft going up from the box
            context.fill(bx + 9, by + 5, bx + 11, by + 13, white)
            // Chevron pointing up
            context.fill(bx + 7, by + 7, bx + 9, by + 8, white)     // left wing
            context.fill(bx + 11, by + 7, bx + 13, by + 8, white)   // right wing
            context.fill(bx + 8, by + 6, bx + 9, by + 7, white)     // left tip
            context.fill(bx + 11, by + 6, bx + 12, by + 7, white)   // right tip
        }
    }
}
