package org.zephbyte.resourcepackprofiles.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.input.KeyEvent

/**
 * Vanilla selection list (scrolling, keyboard navigation, narration) with the mod's flat chrome:
 * no background or separators, rows draw their own highlight, and a thin flat scrollbar.
 */
abstract class FlatSelectionList<E : ObjectSelectionList.Entry<E>>(
    minecraft: Minecraft, w: Int, h: Int, top: Int, itemHeight: Int
) : ObjectSelectionList<E>(minecraft, w, h, top, itemHeight) {

    override fun getRowWidth(): Int = width - 10

    override fun scrollBarX(): Int = x + width - scrollbarWidth()

    // Vanilla pads only the top of the contents (4px). Pad the end by the same amount, so a
    // list scrolled to the bottom ends with the same space it starts with.
    override fun contentHeight(): Int = super.contentHeight() + 4

    // Thin flat scrollbar in place of the vanilla sprites, matching the popups. The vanilla
    // hit area (its full width) is unchanged, so dragging and clicking the track still work.
    override fun extractScrollbar(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        // Still run the original, but clipped to an empty area so its sprites don't show.
        // Other mods hook this method (Smooth Scrolling animates the wheel from it), so
        // skipping it broke wheel scrolling for anyone with such a mod installed.
        context.enableScissor(0, 0, 0, 0)
        try {
            super.extractScrollbar(context, mouseX, mouseY)
        } finally {
            context.disableScissor()
        }
        if (!scrollable()) return
        val barWidth = 2
        val barX = scrollBarX() + scrollbarWidth() - barWidth - 1
        // Track inset by the same 2px at the top and bottom, with the thumb mapped into it.
        val inset = 2
        val trackTop = y + inset
        val trackHeight = height - inset * 2
        context.fill(barX, trackTop, barX + barWidth, trackTop + trackHeight, Theme.DIVIDER)
        val hot = isOverScrollbar(mouseX.toDouble(), mouseY.toDouble())
        val thumbHeight = scrollerHeight().coerceAtMost(trackHeight)
        val travel = (height - scrollerHeight()).coerceAtLeast(1)
        val thumbTop = trackTop + (scrollBarY() - y) * (trackHeight - thumbHeight) / travel
        context.fill(barX, thumbTop, barX + barWidth, thumbTop + thumbHeight, if (hot) Theme.TEXT else Theme.TEXT_SECONDARY)
    }

    // The panel behind the list is the background; rows draw their own highlight.
    override fun extractListBackground(context: GuiGraphicsExtractor) {}
    override fun extractListSeparators(context: GuiGraphicsExtractor) {}
    override fun extractSelection(context: GuiGraphicsExtractor, entry: E, color: Int) {}

    // Give the selected entry first crack at the key, then fall back to list navigation.
    override fun keyPressed(event: KeyEvent): Boolean {
        val sel = selected
        if (sel != null && sel.keyPressed(event)) return true
        return super.keyPressed(event)
    }

    /** True when scrolled to within [margin] pixels of the end, e.g. to load the next page. */
    fun nearEnd(margin: Int = 40): Boolean = scrollAmount() >= maxScrollAmount() - margin
}
