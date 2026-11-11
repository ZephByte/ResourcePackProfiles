package org.zephbyte.resourcepackprofiles.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Colors and drawing primitives for the mod's flat GUI. Everything is drawn with plain fills, so
 * the look needs no texture assets and is unaffected by resource packs that restyle vanilla.
 *
 * The style follows modern Minecraft menus such as Sodium's: no outlines, with areas told apart
 * by steps in shade (a darker header block on a panel, raised popups) and filled controls.
 */
object Theme {
    /** Main panels. */
    const val PANEL_FILL = 0xC0101114.toInt()
    /** Laid over a panel to darken its header area, separating it without a line. */
    const val HEADER_FILL = 0x70000000
    /** Popups, menus and dialogs, which sit above everything else. */
    const val RAISED_FILL = 0xF21B1D22.toInt()
    const val DIVIDER = 0xFF2A2D36.toInt()

    const val ROW_HOVER = 0x1EFFFFFF
    const val ROW_SELECTED = 0x38FFFFFF

    /** Section titles, the selected row's bar, focus marks. */
    const val ACCENT = 0xFFA8C4E6.toInt()

    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFFA0A4AD.toInt()
    const val TEXT_MUTED = 0xFF6C6F78.toInt()
    const val TEXT_ON_PRIMARY = 0xFF111317.toInt()

    /** Marks the profile that is currently applied. */
    const val ACTIVE = 0xFFF5D463.toInt()
    const val SUCCESS = 0xFF7FD08A.toInt()
    const val DANGER = 0xFFE06C6C.toInt()
    const val WARNING = 0xFFE0A84A.toInt()
    private const val BADGE_OUTLINE = 0xFF101114.toInt()

    const val CONTROL_BORDER = 0xFF4A4D56.toInt()
    const val CONTROL_BORDER_FOCUS = 0xFFDEDEDE.toInt()
    /** Buttons are filled, not outlined: this resting fill, [CONTROL_HOVER] under the pointer. */
    const val CONTROL_FILL = 0x24FFFFFF
    const val CONTROL_HOVER = 0x40FFFFFF
    const val CONTROL_DISABLED = 0x10FFFFFF
    const val FIELD_FILL = 0x80000000.toInt()
    const val PRIMARY_FILL = 0xFFE6E6E6.toInt()
    const val PRIMARY_FILL_HOVER = 0xFFFFFFFF.toInt()

    /** Translucent panel. No outline: neighbouring areas differ by shade instead. */
    fun panel(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        context.fill(x, y, x + width, y + height, PANEL_FILL)
    }

    /** Darkens the header area at the top of a panel. */
    fun header(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        context.fill(x, y, x + width, y + height, HEADER_FILL)
    }

    /** Surface for popups and dialogs: more opaque, and lighter than the panels below it. */
    fun raised(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        context.fill(x, y, x + width, y + height, RAISED_FILL)
    }

    /** 1px border drawn just inside the given rectangle. */
    fun border(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, color: Int) {
        context.fill(x, y, x + width, y + 1, color)
        context.fill(x, y + height - 1, x + width, y + height, color)
        context.fill(x, y + 1, x + 1, y + height - 1, color)
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, color)
    }

    /** Background for a text field: a dark well, with an accent underline while [focused]. */
    fun field(context: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, focused: Boolean) {
        context.fill(x, y, x + width, y + height, FIELD_FILL)
        if (focused) context.fill(x, y + height - 1, x + width, y + height, ACCENT)
    }

    /**
     * The "applied" marker: a yellow check over the bottom-right corner of an icon at
     * ([iconX], [iconY]) that is [size] pixels square. A dark outline keeps it readable on top
     * of any icon artwork.
     */
    fun appliedBadge(context: GuiGraphicsExtractor, iconX: Int, iconY: Int, size: Int) {
        outlined(context, Icon.CHECK, iconX + size - Icon.CHECK.width + 2, iconY + size - Icon.CHECK.height + 2, ACTIVE)
    }

    /**
     * Draws [icon] in [color] with a 1px dark outline, so markers drawn over artwork or a busy
     * background (the applied check, the favourite star) stay readable.
     */
    fun outlined(context: GuiGraphicsExtractor, icon: Icon, x: Int, y: Int, color: Int) {
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx != 0 || dy != 0) icon.draw(context, x + dx, y + dy, BADGE_OUTLINE)
            }
        }
        icon.draw(context, x, y, color)
    }

    /** Scales the alpha channel of [color] by [alpha] (0..1). */
    fun withAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
