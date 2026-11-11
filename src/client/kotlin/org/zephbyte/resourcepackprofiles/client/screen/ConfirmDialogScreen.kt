package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.zephbyte.resourcepackprofiles.client.ui.FlatButton
import org.zephbyte.resourcepackprofiles.client.ui.Theme
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import org.zephbyte.resourcepackprofiles.client.util.showScreen

/**
 * The mod's confirmation dialog, used instead of the vanilla one so every dialog shares the flat
 * look. Shows a title, a wrapped [message], and optionally a scrolling list of [items] that the
 * action will affect (e.g. the packs about to be deleted).
 *
 * [onConfirm] runs before returning to [parent], so the parent rebuilds with the result. Cancel
 * and Esc return without calling it.
 */
class ConfirmDialogScreen(
    private val parent: Screen,
    title: Component,
    private val message: Component,
    private val confirmLabel: Component,
    private val items: List<String> = emptyList(),
    /** Draws the confirm button in red, for actions that delete something. */
    private val destructive: Boolean = true,
    /** Label and action of the second button. Esc never runs [onCancel]; it only closes. */
    private val cancelLabel: Component = CommonComponents.GUI_CANCEL,
    private val onCancel: () -> Unit = {},
    private val onConfirm: () -> Unit
) : Screen(title) {

    private var scroll = 0

    private val panelWidth get() = (width - 40).coerceAtMost(320)
    private val panelX get() = (width - panelWidth) / 2
    private val left get() = panelX + 12
    private val inner get() = panelWidth - 24

    private val messageLines get() = font.split(message, inner)
    private val visibleRows get() = minOf(items.size, MAX_ROWS)
    private val listHeight get() = if (items.isEmpty()) 0 else visibleRows * ROW_HEIGHT + 6

    // Vertical layout, top to bottom: title, message, optional list, buttons.
    private val messageTop get() = panelY + 28
    private val listTop get() = messageTop + messageLines.size * LINE_HEIGHT + 6
    private val buttonTop get() = (if (items.isEmpty()) listTop else listTop + listHeight + 8)
    private val panelHeight get() = 28 + messageLines.size * LINE_HEIGHT + 6 + (if (items.isEmpty()) 0 else listHeight + 8) + 16 + 12
    private val panelY get() = ((height - panelHeight) / 2).coerceAtLeast(4)

    override fun init() {
        val gap = 4
        val buttonWidth = (inner - gap) / 2
        addRenderableWidget(
            FlatButton(
                left, buttonTop, buttonWidth, 16, confirmLabel,
                style = if (destructive) FlatButton.Style.DANGER else FlatButton.Style.PRIMARY
            ) {
                onConfirm()
                minecraft.showScreen(parent)
            }
        )
        addRenderableWidget(
            FlatButton(left + buttonWidth + gap, buttonTop, inner - buttonWidth - gap, 16, cancelLabel) {
                onCancel()
                minecraft.showScreen(parent)
            }
        )
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val maxScroll = (items.size - MAX_ROWS).coerceAtLeast(0)
        if (maxScroll == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        scroll = (scroll - scrollY.toInt().coerceIn(-3, 3)).coerceIn(0, maxScroll)
        return true
    }

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
        Theme.raised(context, panelX, panelY, panelWidth, panelHeight)
        if (items.isNotEmpty()) Theme.field(context, left, listTop, inner, listHeight, false)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
        context.text(font, title, left, panelY + 12, Theme.TEXT, true)
        messageLines.forEachIndexed { i, line ->
            context.text(font, line, left, messageTop + i * LINE_HEIGHT, Theme.TEXT_SECONDARY, true)
        }
        if (items.isEmpty()) return

        val scrollable = items.size > MAX_ROWS
        val textWidth = inner - 10 - (if (scrollable) 5 else 0)
        items.drop(scroll).take(MAX_ROWS).forEachIndexed { i, item ->
            context.text(font, ScreenUtils.truncate(font, item, textWidth), left + 5, listTop + 4 + i * ROW_HEIGHT, Theme.TEXT, true)
        }
        if (scrollable) {
            // Thin scroll indicator: track plus a thumb sized to the visible fraction.
            val trackTop = listTop + 2
            val trackHeight = listHeight - 4
            val thumbHeight = (trackHeight * MAX_ROWS / items.size).coerceAtLeast(6)
            val thumbTop = trackTop + (trackHeight - thumbHeight) * scroll / (items.size - MAX_ROWS)
            val barX = left + inner - 4
            context.fill(barX, trackTop, barX + 2, trackTop + trackHeight, Theme.DIVIDER)
            context.fill(barX, thumbTop, barX + 2, thumbTop + thumbHeight, Theme.TEXT_SECONDARY)
        }
    }

    override fun onClose() {
        minecraft.showScreen(parent)
    }

    private companion object {
        const val LINE_HEIGHT = 10
        const val ROW_HEIGHT = 10
        const val MAX_ROWS = 8
    }
}
