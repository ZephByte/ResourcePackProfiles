package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.zephbyte.resourcepackprofiles.client.RefreshablePackScreen
import org.zephbyte.resourcepackprofiles.client.profile.ImportResult
import org.zephbyte.resourcepackprofiles.client.profile.ProfileIconManager
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.profile.ResourcePackProfile
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import java.nio.file.Path

class ProfileScreen(private val parent: Screen?) : Screen(Component.translatable("screen.resourcepackprofiles.title")) {

    private lateinit var nameField: EditBox
    private var scrollOffset = 0
    // Tracks whether a profile was applied this session, so onClose can return to a refreshed
    // pack selection screen instead of a stale one that would overwrite the applied packs.
    private var profileApplied = false

    private val entryHeight = 26
    private val listTop = 32
    private var listBottom = 0
    private var maxVisibleEntries = 0
    private val iconSize = 21

    // Row layout anchors, all relative to the screen centre.
    private val iconX get() = width / 2 - 150
    private val nameX get() = iconX + iconSize + 4
    private val rowTextRightEdge get() = width / 2 + 52
    private val starX get() = width / 2 + 62
    private val editX get() = width / 2 + 84
    private val deleteX get() = width / 2 + 106
    private val rowButtonSize = 20

    override fun init() {
        listBottom = height - 56
        maxVisibleEntries = (listBottom - listTop) / entryHeight

        nameField = EditBox(font, width / 2 - 152, height - 52, 200, 20, Component.translatable("field.resourcepackprofiles.profile_name"))
        nameField.setMaxLength(64)
        nameField.setHint(Component.translatable("field.resourcepackprofiles.profile_name.hint"))

        rebuildProfileButtons()
    }

    private fun rebuildProfileButtons() {
        clearWidgets()

        addRenderableWidget(nameField)

        addRenderableWidget(Button.builder(Component.translatable("button.resourcepackprofiles.save_current")) { onSave() }
            .bounds(width / 2 + 52, height - 52, 100, 20)
            .build())

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }
            .bounds(width / 2 - 50, height - 28, 100, 20)
            .build())

        addRenderableWidget(Button.builder(Component.literal(" ")) { onImport() }
            .bounds(width / 2 + 54, height - 28, rowButtonSize, rowButtonSize)
            .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.import")))
            .build())

        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val buttonY = listTop + index * entryHeight + 1

            val starLabel = if (profile.favorite) "★" else "☆"
            addRenderableWidget(Button.builder(Component.literal(starLabel)) {
                ProfileManager.toggleFavorite(profile.name)
                rebuildProfileButtons()
            }.bounds(starX, buttonY, rowButtonSize, rowButtonSize).build())

            addRenderableWidget(Button.builder(Component.literal("✎")) { minecraft?.setScreen(EditProfileScreen(this, profile.name)) }
                .bounds(editX, buttonY, rowButtonSize, rowButtonSize)
                .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.edit")))
                .build())

            addRenderableWidget(Button.builder(Component.literal("✕")) { onDelete(profile.name) }
                .bounds(deleteX, buttonY, rowButtonSize, rowButtonSize)
                .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.delete")))
                .build())
        }
    }

    private fun onSave() {
        val name = nameField.value.trim()
        if (name.isEmpty()) return

        if (ProfileManager.hasProfile(name)) {
            minecraft?.setScreen(ConfirmScreen(
                { confirmed ->
                    if (confirmed) saveProfile(name)
                    minecraft?.setScreen(this)
                },
                Component.translatable("screen.resourcepackprofiles.overwrite.title"),
                Component.translatable("screen.resourcepackprofiles.overwrite.message", name)
            ))
        } else {
            saveProfile(name)
        }
    }

    private fun saveProfile(name: String) {
        ProfileManager.saveCurrentAsProfile(name)
        nameField.value = ""
        scrollOffset = 0
        rebuildProfileButtons()
    }

    private fun onLoad(profile: ResourcePackProfile) {
        if (ProfileManager.isActiveProfile(profile)) return
        minecraft?.setScreen(ConfirmScreen(
            { confirmed ->
                if (confirmed) {
                    val missingIds = ProfileManager.applyProfile(profile)
                    profileApplied = true
                    if (missingIds.isNotEmpty()) {
                        minecraft?.setScreen(MissingPacksScreen(this, missingIds))
                        return@ConfirmScreen
                    }
                }
                minecraft?.setScreen(this)
            },
            Component.translatable("screen.resourcepackprofiles.load.title"),
            Component.translatable("screen.resourcepackprofiles.load.message", profile.name)
        ))
    }

    private fun onDelete(name: String) {
        minecraft?.setScreen(ConfirmScreen(
            { confirmed ->
                if (confirmed) {
                    ProfileManager.deleteProfile(name)
                    scrollOffset = 0
                    rebuildProfileButtons()
                }
                minecraft?.setScreen(this)
            },
            Component.translatable("screen.resourcepackprofiles.delete.title"),
            Component.translatable("screen.resourcepackprofiles.delete.message", name)
        ))
    }

    private fun onImport() {
        Thread {
            val pathStr = ProfileManager.pickProfileToImport() ?: return@Thread
            val filePath = Path.of(pathStr)
            Minecraft.getInstance().execute { handleImport(filePath) }
        }.start()
    }

    private fun handleImport(filePath: Path, overwriteName: String? = null) {
        when (val result = ProfileManager.importProfileFromPath(filePath, overwriteName)) {
            is ImportResult.Conflict -> minecraft?.setScreen(ConfirmScreen(
                { confirmed ->
                    if (confirmed) handleImport(filePath, result.name)
                    else minecraft?.setScreen(this)
                },
                Component.translatable("screen.resourcepackprofiles.overwrite.title"),
                Component.translatable("screen.resourcepackprofiles.overwrite.message", result.name)
            ))
            is ImportResult.Imported -> {
                scrollOffset = 0
                rebuildProfileButtons()
                minecraft?.setScreen(this)
            }
            ImportResult.Failed -> minecraft?.setScreen(this)
        }
    }

    private fun getProfileSubLabel(profile: ResourcePackProfile): Component {
        val missingCount = getMissingPackCount(profile)
        return if (missingCount > 0) {
            Component.translatable("label.resourcepackprofiles.pack_count_missing", profile.packIds.size, missingCount)
        } else {
            Component.translatable("label.resourcepackprofiles.pack_count", profile.packIds.size)
        }
    }

    private fun getMissingPackCount(profile: ResourcePackProfile): Int {
        val allIds = minecraft!!.resourcePackRepository.availablePacks.map { it.id }.toSet()
        return profile.packIds.count { it !in allIds }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)

        // Draw import icon on the import button
        ScreenUtils.drawTransferIcon(context, width / 2 + 54, height - 28, into = true)

        // Title
        context.centeredText(font, title, width / 2, 16, 0xFFFFFFFF.toInt())

        // Profile list with icons
        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight
            val iconY = y + 1

            // Active profile outline
            if (ProfileManager.isActiveProfile(profile)) {
                val outlineColor = 0xFFAAAAAA.toInt()
                val left = iconX - 2
                val top = y - 2
                val right = width / 2 + 130
                val bottom = y + entryHeight - 2
                context.fill(left, top, right, top + 1, outlineColor)         // top
                context.fill(left, bottom, right, bottom + 1, outlineColor)   // bottom
                context.fill(left, top, left + 1, bottom + 1, outlineColor)   // left
                context.fill(right - 1, top, right, bottom + 1, outlineColor) // right
            }

            // Draw icon
            val iconId = ProfileIconManager.getIconId(profile)
            context.blit(RenderPipelines.GUI_TEXTURED, iconId, iconX, iconY, 0f, 0f, iconSize, iconSize, iconSize, iconSize)

            // Draw name label shifted right to make room for icon — highlight on hover
            val maxTextWidth = rowTextRightEdge - nameX
            val label = ScreenUtils.truncate(font, profile.name, maxTextWidth)
            val nameWidth = font.width(label)
            val isHoveringName = mouseX >= nameX && mouseX < nameX + nameWidth && mouseY >= y && mouseY < y + entryHeight
            val nameColor = if (isHoveringName) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt()
            context.text(font, Component.literal(label), nameX, y + 2, nameColor, true)

            // Pack count
            val missingCount = getMissingPackCount(profile)
            val subLabel = getProfileSubLabel(profile)
            val subColor = if (missingCount > 0) 0xFFFF5555.toInt() else 0xFFAAAAAA.toInt()
            context.text(font, subLabel, nameX, y + 14, subColor, false)

            // Red tint on trash button when hovered
            val buttonY = y + 1
            val trashHovered = mouseX >= deleteX && mouseX < deleteX + rowButtonSize && mouseY >= buttonY && mouseY < buttonY + rowButtonSize
            if (trashHovered) {
                context.fill(deleteX, buttonY, deleteX + rowButtonSize, buttonY + rowButtonSize, 0x60FF0000)
            }
        }

        if (profiles.isEmpty()) {
            context.centeredText(
                font,
                Component.translatable("label.resourcepackprofiles.no_profiles"),
                width / 2,
                listTop + 20,
                0xFFAAAAAA.toInt()
            )
        }

        // Scrollbar
        if (profiles.size > maxVisibleEntries) {
            val listHeight = listBottom - listTop
            val totalContentHeight = profiles.size * entryHeight
            val barHeight = (listHeight.toDouble() * listHeight / totalContentHeight).toInt().coerceAtLeast(8)
            val maxScroll = profiles.size - maxVisibleEntries
            val barY = listTop + ((listHeight - barHeight) * scrollOffset.toDouble() / maxScroll).toInt()
            val barX = width / 2 + 132
            context.fill(barX, barY, barX + 3, barY + barHeight, 0x80FFFFFF.toInt())
        }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val mouseX = click.x()
        val mouseY = click.y()

        // Click on a profile name → load that profile
        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight
            val maxTextWidth = rowTextRightEdge - nameX
            val label = ScreenUtils.truncate(font, profile.name, maxTextWidth)
            val nameWidth = font.width(label)
            if (mouseX >= nameX && mouseX < nameX + nameWidth && mouseY >= y && mouseY < y + entryHeight) {
                onLoad(profile)
                return true
            }
        }

        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val profiles = ProfileManager.getProfiles()
        val maxScroll = (profiles.size - maxVisibleEntries).coerceAtLeast(0)
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
        rebuildProfileButtons()
        return true
    }

    override fun onClose() {
        ProfileIconManager.cleanup()
        val returnTo = parent
        // If we came from the vanilla pack selection screen and applied a profile, its model holds
        // a stale snapshot that would wipe the applied packs on close. Return to a refreshed
        // instance whose model reflects the packs we just applied instead.
        if (profileApplied && returnTo is RefreshablePackScreen) {
            minecraft?.setScreen(returnTo.rpp_createRefreshedScreen())
        } else {
            minecraft?.setScreen(returnTo)
        }
    }
}
