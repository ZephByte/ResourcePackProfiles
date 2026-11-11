package org.zephbyte.resourcepackprofiles.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Flat tick box with a label to its right. The widget is exactly as wide as the box plus the
 * label ([widthFor]), so callers can centre it. Extends [AbstractWidget] to keep vanilla focus
 * handling, tab order and narration.
 */
class FlatCheckbox(
    x: Int, y: Int, font: Font,
    label: Component,
    var checked: Boolean = false,
    private val onChange: (Boolean) -> Unit = {}
) : AbstractWidget(x, y, widthFor(font, label), BOX_SIZE, label) {

    override fun extractWidgetRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val hot = isHoveredOrFocused
        context.fill(x, y, x + BOX_SIZE, y + BOX_SIZE, Theme.FIELD_FILL)
        Theme.border(context, x, y, BOX_SIZE, BOX_SIZE, if (hot) Theme.CONTROL_BORDER_FOCUS else Theme.CONTROL_BORDER)
        if (checked) context.fill(x + 3, y + 3, x + BOX_SIZE - 3, y + BOX_SIZE - 3, Theme.SUCCESS)
        val font = Minecraft.getInstance().font
        context.text(font, message, x + BOX_SIZE + GAP, y + (BOX_SIZE - 8) / 2, if (hot) Theme.TEXT else Theme.TEXT_SECONDARY, true)
    }

    private fun toggle() {
        checked = !checked
        onChange(checked)
    }

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) = toggle()

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!active || !visible || !event.isSelection) return false
        playButtonClickSound(Minecraft.getInstance().soundManager)
        toggle()
        return true
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)

    companion object {
        private const val BOX_SIZE = 11
        private const val GAP = 5

        /** Total width of a checkbox with this [label]. */
        fun widthFor(font: Font, label: Component): Int = BOX_SIZE + GAP + font.width(label)
    }
}
