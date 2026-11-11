package org.zephbyte.resourcepackprofiles.client.ui

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import org.zephbyte.resourcepackprofiles.client.update.DownloadProgress
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils

/**
 * The status message shown along the bottom of the mod's screens: a small panel that can carry a
 * download progress bar, with a compact list of what is downloading on hover.
 */
object StatusPill {
    const val HEIGHT = 18

    /**
     * Draws [text] on its own small panel at ([x], [y]), no wider than up to [right]. With a
     * [fraction] it also draws a progress bar along the bottom edge. Returns the panel's x, y,
     * width and height, or null if there was no room to draw it.
     */
    fun draw(
        context: GuiGraphicsExtractor, font: Font, text: Component, color: Int, alpha: Float,
        x: Int, y: Int, right: Int, fraction: Float? = null
    ): IntArray? {
        val padX = 6
        val maxText = right - x - padX * 2
        if (maxText <= 0) return null
        val shown = ScreenUtils.truncate(font, text.string, maxText)
        val barHeight = if (fraction != null) 2 else 0
        // Wide enough for the bar to read as a bar, but never past [right].
        val w = (font.width(shown) - 1 + padX * 2).coerceAtLeast(if (fraction != null) 140 else 0).coerceAtMost(right - x)
        context.fill(x, y, x + w, y + HEIGHT, Theme.withAlpha(Theme.RAISED_FILL, alpha))
        context.text(font, shown, x + padX, y + (HEIGHT - barHeight - 7) / 2, Theme.withAlpha(color, alpha), true)
        if (fraction != null) {
            context.fill(x, y + HEIGHT - barHeight, x + w, y + HEIGHT, Theme.CONTROL_FILL)
            context.fill(x, y + HEIGHT - barHeight, x + (w * fraction).toInt(), y + HEIGHT, Theme.ACTIVE)
        }
        return intArrayOf(x, y, w, HEIGHT)
    }

    /** Draws the download bar for [batch] and shows its details while the pointer is over it. */
    fun drawProgress(
        context: GuiGraphicsExtractor, font: Font, batch: DownloadProgress,
        x: Int, y: Int, right: Int, mouseX: Int, mouseY: Int
    ) {
        val label = Component.translatable("status.resourcepackprofiles.downloading_progress", batch.finished, batch.total)
        val bounds = draw(context, font, label, Theme.ACTIVE, 1f, x, y, right, batch.fraction()) ?: return
        if (mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3]) {
            context.setComponentTooltipForNextFrame(font, progressLines(font, batch), mouseX, mouseY)
        }
    }

    /**
     * Hover text for the download bar. Kept to a handful of lines however long the batch is: the
     * count, what is downloading now, and the next few, last few finished and failed files.
     */
    fun progressLines(font: Font, batch: DownloadProgress): List<Component> {
        fun more(key: String, count: Int) = Component.translatable(key, count).string
        fun names(items: List<DownloadProgress.Item>, limit: Int): String {
            val shown = items.take(limit).joinToString(", ") { ScreenUtils.truncate(font, it.name, 150) }
            return if (items.size > limit) "$shown, " + more("tooltip.resourcepackprofiles.progress_more", items.size - limit) else shown
        }
        fun line(key: String, color: ChatFormatting, text: String): Component =
            Component.translatable(key, text).withStyle(color)

        val lines = mutableListOf<Component>(
            Component.translatable(
                "tooltip.resourcepackprofiles.progress_title", batch.finished, batch.total, (batch.fraction() * 100).toInt()
            )
        )
        val state = { wanted: DownloadProgress.State -> batch.items.filter { it.state == wanted } }
        state(DownloadProgress.State.ACTIVE).firstOrNull()?.let {
            lines.add(line("tooltip.resourcepackprofiles.progress_now", ChatFormatting.YELLOW, ScreenUtils.truncate(font, it.name, 200)))
        }
        state(DownloadProgress.State.WAITING).takeIf { it.isNotEmpty() }?.let {
            lines.add(line("tooltip.resourcepackprofiles.progress_next", ChatFormatting.GRAY, names(it, 2)))
        }
        // The most recent finished files, not the first ones: those are the ones just completed.
        state(DownloadProgress.State.DONE).takeIf { it.isNotEmpty() }?.let {
            val earlier = if (it.size > 2) ", " + more("tooltip.resourcepackprofiles.progress_earlier", it.size - 2) else ""
            lines.add(line("tooltip.resourcepackprofiles.progress_done", ChatFormatting.GREEN, names(it.takeLast(2).reversed(), 2) + earlier))
        }
        state(DownloadProgress.State.FAILED).takeIf { it.isNotEmpty() }?.let {
            lines.add(line("tooltip.resourcepackprofiles.progress_failed", ChatFormatting.RED, names(it, 2)))
        }
        return lines
    }
}
