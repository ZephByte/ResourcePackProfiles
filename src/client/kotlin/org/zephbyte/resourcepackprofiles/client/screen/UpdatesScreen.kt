package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.update.PackCheck
import org.zephbyte.resourcepackprofiles.client.update.PackUpdate
import org.zephbyte.resourcepackprofiles.client.update.UpdateChecker
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import org.zephbyte.resourcepackprofiles.client.util.showScreen

/** A profile's pack list that updated packs should be swapped into. */
class UpdateSource(val profileName: String, val packIds: List<String>)

/**
 * Checks packs against Modrinth and lets the user pick which updates to download. Confirming
 * downloads the new files (the old ones are kept) and saves an updated copy of every [sources]
 * profile that contained an updated pack.
 *
 * @param onlyPackIds restricts the check to these packs; null checks every pack in [sources].
 */
class UpdatesScreen(
    private val parent: Screen,
    private val sources: List<UpdateSource>,
    private val onlyPackIds: Set<String>? = null
) : Screen(Component.translatable("screen.resourcepackprofiles.updates.title")) {

    private enum class Phase { CHECKING, READY, DOWNLOADING }

    private class Row(val update: PackUpdate, var selected: Boolean = true)

    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    // Written by worker threads, read on the render thread.
    @Volatile private var phase = Phase.CHECKING
    @Volatile private var pendingReport: Map<String, PackCheck>? = null
    @Volatile private var error: Component? = null

    private var started = false
    private var rows: List<Row> = emptyList()
    private var summary: Component = Component.empty()

    private lateinit var list: UpdateList
    private lateinit var createButton: Button

    private val listTop = 32
    private val entryHeight = 28
    private val listBottom get() = height - 56

    override fun init() {
        list = UpdateList(minecraft)
        addRenderableWidget(list)
        list.refresh()

        createButton = Button.builder(Component.translatable("button.resourcepackprofiles.create_updated")) { onCreate() }
            .bounds(width / 2 - 154, height - 28, 150, 20)
            .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.create_updated")))
            .build()
        addRenderableWidget(createButton)

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK) { onClose() }
            .bounds(width / 2 + 4, height - 28, 150, 20)
            .build())

        // init() reruns on window resize; only start the check once.
        if (!started) {
            started = true
            startCheck()
        }
    }

    private fun startCheck() {
        val ids = sources.flatMap { it.packIds }.distinct()
            .filter { onlyPackIds == null || it in onlyPackIds }
        Thread {
            try {
                pendingReport = UpdateChecker.check(ids)
            } catch (e: Exception) {
                logger.error("Update check failed", e)
                error = Component.translatable("label.resourcepackprofiles.updates_error", e.message ?: e.javaClass.simpleName)
            }
            phase = Phase.READY
        }.also { it.name = "RPP-UpdateCheck"; it.isDaemon = true }.start()
    }

    private fun onCreate() {
        if (phase != Phase.READY) return
        val chosen = rows.filter { it.selected }.map { it.update }
        if (chosen.isEmpty()) return
        phase = Phase.DOWNLOADING
        error = null

        Thread {
            try {
                val replacements = chosen.associate { it.packId to UpdateChecker.download(it) }
                Minecraft.getInstance().execute { finish(replacements) }
            } catch (e: Exception) {
                logger.error("Failed to download pack updates", e)
                error = Component.translatable("label.resourcepackprofiles.updates_error", e.message ?: e.javaClass.simpleName)
                phase = Phase.READY
            }
        }.also { it.name = "RPP-UpdateDownload"; it.isDaemon = true }.start()
    }

    /** Render thread: all files are on disk, so register them and fork the affected profiles. */
    private fun finish(replacements: Map<String, String>) {
        minecraft.resourcePackRepository.reload()
        for (source in sources) {
            if (source.packIds.any { it in replacements }) {
                ProfileManager.createUpdatedProfile(source.profileName, source.packIds, replacements)
            }
        }
        minecraft.showScreen(parent)
    }

    private fun applyReport(report: Map<String, PackCheck>) {
        val updates = report.values.filterIsInstance<PackCheck.UpdateAvailable>().map { it.update }
        rows = updates.sortedBy { it.projectTitle.lowercase() }.map { Row(it) }
        summary = Component.translatable(
            "label.resourcepackprofiles.updates_summary",
            report.values.count { it is PackCheck.UpToDate },
            report.values.count { it is PackCheck.NotOnModrinth },
            report.values.count { it is PackCheck.NoCompatibleVersion }
        )
        list.refresh()
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        pendingReport?.let {
            pendingReport = null
            applyReport(it)
        }
        createButton.active = phase == Phase.READY && rows.any { it.selected }

        super.extractRenderState(context, mouseX, mouseY, delta)
        context.centeredText(font, title, width / 2, 16, 0xFFFFFFFF.toInt())

        val midY = listTop + (listBottom - listTop) / 2
        when {
            phase == Phase.CHECKING ->
                context.centeredText(font, Component.translatable("label.resourcepackprofiles.updates_checking"), width / 2, midY, 0xFFAAAAAA.toInt())
            phase == Phase.DOWNLOADING ->
                context.centeredText(font, Component.translatable("label.resourcepackprofiles.updates_downloading"), width / 2, listBottom + 8, 0xFFFFFF55.toInt())
            rows.isEmpty() && error == null ->
                context.centeredText(font, Component.translatable("label.resourcepackprofiles.updates_none"), width / 2, midY, 0xFFAAAAAA.toInt())
        }
        val err = error
        if (err != null) {
            context.centeredText(font, err, width / 2, listBottom + 8, 0xFFFF5555.toInt())
        } else if (phase == Phase.READY) {
            context.centeredText(font, summary, width / 2, listBottom + 8, 0xFF808080.toInt())
        }
    }

    override fun onClose() {
        // Closing mid-download would leave a half-finished update with no feedback.
        if (phase == Phase.DOWNLOADING) return
        minecraft.showScreen(parent)
    }

    private inner class UpdateList(mc: Minecraft) :
        ObjectSelectionList<UpdateEntry>(mc, this@UpdatesScreen.width, listBottom - listTop, listTop, entryHeight) {

        override fun getRowWidth(): Int = 300

        fun refresh() {
            replaceEntries(rows.map { UpdateEntry(it) })
        }
    }

    private inner class UpdateEntry(private val row: Row) : ObjectSelectionList.Entry<UpdateEntry>() {

        override fun getNarration(): Component = Component.literal(row.update.projectTitle)

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            if (phase != Phase.READY) return true
            row.selected = !row.selected
            return true
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val centerY = y + height / 2
            val boxSize = 11
            val boxX = contentX + 6
            val boxY = centerY - boxSize / 2

            // Checkbox: light border, dark fill, inner square when selected.
            context.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, 0xFFAAAAAA.toInt())
            context.fill(boxX + 1, boxY + 1, boxX + boxSize - 1, boxY + boxSize - 1, 0xFF000000.toInt())
            if (row.selected) {
                context.fill(boxX + 3, boxY + 3, boxX + boxSize - 3, boxY + boxSize - 3, 0xFF55FF55.toInt())
            }

            val textX = boxX + boxSize + 6
            val maxWidth = contentRight - 6 - textX
            val nameColor = if (hovered) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt()
            context.text(font, Component.literal(ScreenUtils.truncate(font, row.update.projectTitle, maxWidth)), textX, centerY - 9, nameColor, true)

            val oldFile = row.update.packId.removePrefix("file/")
            val detail = "$oldFile → ${row.update.fileName}"
            context.text(font, Component.literal(ScreenUtils.truncate(font, detail, maxWidth)), textX, centerY + 1, 0xFF808080.toInt(), false)
        }
    }
}
