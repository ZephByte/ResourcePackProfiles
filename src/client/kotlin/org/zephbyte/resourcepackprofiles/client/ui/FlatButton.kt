package org.zephbyte.resourcepackprofiles.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Flat button: a text label and/or an [Icon] on a bordered or filled rectangle. Extends
 * [AbstractWidget] so it keeps vanilla focus handling, tab order, tooltips and narration.
 */
class FlatButton(
    x: Int, y: Int, width: Int, height: Int,
    label: Component,
    private val icon: Icon? = null,
    var style: Style = Style.GHOST,
    /** Draw the label next to the icon; when false the label is only used for narration. */
    private val showLabel: Boolean = icon == null,
    /**
     * Paints the panel colour behind the button. For buttons that sit directly over the world
     * or menu background instead of on a panel, where a see-through button would be unreadable.
     */
    private val backdrop: Boolean = false,
    private val onPress: (FlatButton) -> Unit
) : AbstractWidget(x, y, width, height, label) {

    enum class Style { GHOST, PRIMARY, SUCCESS, DANGER }

    fun withTooltip(text: Component): FlatButton {
        setTooltip(Tooltip.create(text))
        return this
    }

    override fun extractWidgetRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val hot = active && isHoveredOrFocused
        if (backdrop && style != Style.PRIMARY) context.fill(x, y, x + width, y + height, Theme.PANEL_FILL)
        val content: Int
        when {
            !active -> {
                context.fill(x, y, x + width, y + height, Theme.CONTROL_DISABLED)
                content = Theme.TEXT_MUTED
            }
            style == Style.PRIMARY -> {
                context.fill(x, y, x + width, y + height, if (hot) Theme.PRIMARY_FILL_HOVER else Theme.PRIMARY_FILL)
                content = Theme.TEXT_ON_PRIMARY
            }
            else -> {
                val accent = when (style) {
                    Style.SUCCESS -> Theme.SUCCESS
                    Style.DANGER -> Theme.DANGER
                    else -> null
                }
                // Filled, not outlined. Coloured buttons get a tint of their colour.
                val fill = accent?.let { Theme.withAlpha(it, if (hot) 0.38f else 0.22f) }
                    ?: if (hot) Theme.CONTROL_HOVER else Theme.CONTROL_FILL
                context.fill(x, y, x + width, y + height, fill)
                content = accent ?: Theme.TEXT
            }
        }
        // Keyboard focus still needs a visible mark now that buttons have no outline. Only for
        // keyboard use: a clicked button keeps focus, and a ring there would just look stuck.
        if (active && isFocused && Minecraft.getInstance().lastInputType.isKeyboard) {
            Theme.border(context, x, y, width, height, Theme.ACCENT)
        }

        val font = Minecraft.getInstance().font
        val textWidth = if (showLabel) font.width(message) else 0
        val gap = if (icon != null && showLabel) 4 else 0
        // A string's measured width includes one trailing pixel of letter spacing, and glyphs are
        // 7px tall, so centre on that visible box. Otherwise short labels like "?" sit up and left.
        val visibleText = if (showLabel) textWidth - 1 else 0
        var cx = x + (width - (icon?.width ?: 0) - gap - visibleText + 1) / 2
        if (icon != null) {
            icon.draw(context, cx, y + (height - icon.height) / 2, content)
            cx += icon.width + gap
        }
        // Vanilla-style text shadow, except on the light primary fill where it would smudge.
        val shadow = active && style != Style.PRIMARY
        if (showLabel) context.text(font, message, cx, y + (height - 7) / 2, content, shadow)
    }

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) = onPress(this)

    override fun keyPressed(event: KeyEvent): Boolean {
        if (!active || !visible || !event.isSelection) return false
        playButtonClickSound(Minecraft.getInstance().soundManager)
        onPress(this)
        return true
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) = defaultButtonNarrationText(output)
}
