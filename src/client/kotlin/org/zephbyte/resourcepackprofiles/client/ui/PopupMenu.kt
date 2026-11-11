package org.zephbyte.resourcepackprofiles.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component

/** One entry of a [PopupMenu]. */
class MenuItem(
    val label: Component,
    val danger: Boolean = false,
    /** Small right-aligned annotation, e.g. "current" in a version list. */
    val note: Component? = null,
    val muted: Boolean = false,
    /** Disabled items are shown but do nothing and keep the menu open. */
    val enabled: Boolean = true,
    val action: () -> Unit
)

/**
 * A flat popup menu: a column of items drawn above everything else, scrolling when it has more
 * than [MAX_VISIBLE]. The owning screen hands it input first; while open it takes every click
 * (running the item under the pointer, or just closing) and the mouse wheel.
 */
class PopupMenu {
    var items: List<MenuItem>? = null
        private set

    /**
     * What the open menu belongs to, e.g. the pack whose version list it shows. Lets a result
     * that arrives late check that its menu is still the one open.
     */
    var owner: String? = null
        private set

    private var x = 0
    private var y = 0
    private var width = 0
    private var scroll = 0

    val isOpen: Boolean get() = items != null

    /**
     * Opens [items] with the menu's top-right corner at ([right], [top]), kept inside a screen
     * [screenHeight] tall and no further left than [minX].
     */
    fun open(font: Font, items: List<MenuItem>, right: Int, top: Int, screenHeight: Int, minX: Int, owner: String? = null) {
        this.items = items
        this.owner = owner
        scroll = 0
        width = items.maxOf { font.width(it.label) + (it.note?.let { note -> font.width(note) + 12 } ?: 0) } + 16
        if (items.size > MAX_VISIBLE) width += 4
        x = (right - width).coerceAtLeast(minX)
        y = top.coerceAtMost(screenHeight - 4 - height(items)).coerceAtLeast(4)
    }

    /** Opens [items] just below [anchor], right-aligned to it. */
    fun openBelow(font: Font, items: List<MenuItem>, anchor: AbstractWidget, screenHeight: Int, minX: Int) =
        open(font, items, anchor.x + anchor.getWidth(), anchor.y + anchor.getHeight() + 2, screenHeight, minX)

    fun close() {
        items = null
        owner = null
    }

    fun isOver(mouseX: Int, mouseY: Int): Boolean {
        val items = items ?: return false
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height(items)
    }

    /** Handles a click while open: runs the item under the pointer, or just closes. False when closed. */
    fun click(mouseX: Double, mouseY: Double): Boolean {
        if (!isOpen) return false
        val item = itemAt(mouseX, mouseY)
        if (item != null && !item.enabled) return true
        close()
        item?.action?.invoke()
        return true
    }

    /** Scrolls the open menu. False when closed, so the wheel goes to whatever is underneath. */
    fun scroll(amount: Double): Boolean {
        val items = items ?: return false
        val maxScroll = (items.size - MAX_VISIBLE).coerceAtLeast(0)
        scroll = (scroll - amount.toInt().coerceIn(-3, 3)).coerceIn(0, maxScroll)
        return true
    }

    fun draw(context: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int) {
        val items = items ?: return
        // A new stratum guarantees the menu draws above the widgets it overlaps.
        context.nextStratum()
        val menuHeight = height(items)
        Theme.raised(context, x, y, width, menuHeight)

        val scrollable = items.size > MAX_VISIBLE
        val contentRight = x + width - (if (scrollable) 5 else 1)
        val hovered = itemAt(mouseX.toDouble(), mouseY.toDouble())
        items.drop(scroll).take(MAX_VISIBLE).forEachIndexed { i, item ->
            val iy = y + 2 + i * ITEM_HEIGHT
            if (item === hovered && item.enabled) context.fill(x + 1, iy, contentRight, iy + ITEM_HEIGHT, Theme.CONTROL_HOVER)
            val color = when {
                item.danger -> Theme.DANGER
                item.muted -> Theme.TEXT_MUTED
                else -> Theme.TEXT
            }
            context.text(font, item.label, x + 8, iy + 3, color, true)
            item.note?.let { context.text(font, it, contentRight - 7 - font.width(it), iy + 3, Theme.TEXT_MUTED, true) }
        }
        if (scrollable) {
            // Thin scroll indicator: track plus a thumb sized to the visible fraction.
            val trackTop = y + 2
            val trackHeight = menuHeight - 4
            val thumbHeight = (trackHeight * MAX_VISIBLE / items.size).coerceAtLeast(6)
            val maxScroll = items.size - MAX_VISIBLE
            val thumbTop = trackTop + (trackHeight - thumbHeight) * scroll / maxScroll
            context.fill(x + width - 4, trackTop, x + width - 2, trackTop + trackHeight, Theme.DIVIDER)
            context.fill(x + width - 4, thumbTop, x + width - 2, thumbTop + thumbHeight, Theme.TEXT_SECONDARY)
        }
    }

    private fun itemAt(mouseX: Double, mouseY: Double): MenuItem? {
        val items = items ?: return null
        if (mouseX < x || mouseX >= x + width) return null
        val row = ((mouseY - y - 2) / ITEM_HEIGHT).toInt()
        if (mouseY < y + 2 || row >= MAX_VISIBLE) return null
        return items.getOrNull(row + scroll)
    }

    private fun height(items: List<MenuItem>) = minOf(items.size, MAX_VISIBLE) * ITEM_HEIGHT + 4

    companion object {
        const val ITEM_HEIGHT = 14
        const val MAX_VISIBLE = 12
    }
}
