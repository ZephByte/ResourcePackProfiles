package org.zephbyte.resourcepackprofiles.client.screen

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.server.packs.repository.Pack
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import org.zephbyte.resourcepackprofiles.client.profile.PackIcons
import org.zephbyte.resourcepackprofiles.client.profile.ProfileIconManager
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.profile.ResourcePackProfile
import org.zephbyte.resourcepackprofiles.client.util.DynamicTextureCache
import org.zephbyte.resourcepackprofiles.client.util.FileDialogs
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import java.nio.file.Files
import java.nio.file.Path

class EditProfileScreen(
    private val parent: ProfileScreen,
    private val originalName: String
) : Screen(Component.translatable("screen.resourcepackprofiles.edit.title")) {

    private val logger = LoggerFactory.getLogger("ResourcePackProfiles")

    private lateinit var nameField: EditBox

    // Editing state — pack IDs. selectedPacks is stored top-to-bottom display order (reversed
    // relative to load order); availablePacks is everything assignable that isn't selected.
    private var selectedPacks = mutableListOf<String>()
    private var availablePacks = mutableListOf<String>()
    private var allKnownPackIds = setOf<String>()

    private lateinit var availableList: PackList
    private lateinit var selectedList: PackList

    // Staged icon changes — applied on Done, discarded on Cancel (consistent with name/pack edits)
    private var pendingIconPath: Path? = null
    private var pendingIconRemove = false

    // Whether this profile was the active one when editing began; if so, Done re-applies it so
    // the edits stay live instead of silently deactivating the profile.
    private var wasActive = false

    // Snapshot of the profile as it existed when this screen opened. Used for icon preview so
    // extractRenderState doesn't call getProfiles().find every frame.
    private var profileForPreview: ResourcePackProfile? = null

    // Layout constants — mirror the vanilla pack screen: two 200px-wide lists with an 8px gap
    // centred on the screen.
    private val entryHeight = 36
    private val packIconSize = 32
    private val iconInset = 2
    private val listTop = 54
    private val listWidth = 200
    private val listBottom get() = height - 42
    private val leftColX get() = width / 2 - 4 - listWidth
    private val rightColX get() = width / 2 + 4

    // Vanilla arrow sprite IDs
    private val SELECT_HIGHLIGHTED = Identifier.withDefaultNamespace("transferable_list/select_highlighted")
    private val SELECT = Identifier.withDefaultNamespace("transferable_list/select")
    private val UNSELECT_HIGHLIGHTED = Identifier.withDefaultNamespace("transferable_list/unselect_highlighted")
    private val UNSELECT = Identifier.withDefaultNamespace("transferable_list/unselect")
    private val MOVE_UP_HIGHLIGHTED = Identifier.withDefaultNamespace("transferable_list/move_up_highlighted")
    private val MOVE_UP = Identifier.withDefaultNamespace("transferable_list/move_up")
    private val MOVE_DOWN_HIGHLIGHTED = Identifier.withDefaultNamespace("transferable_list/move_down_highlighted")
    private val MOVE_DOWN = Identifier.withDefaultNamespace("transferable_list/move_down")
    private val UNKNOWN_PACK = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png")

    // Texture caches: per-pack list icons, and the staged-icon preview shown next to the name field.
    private val packTextures = DynamicTextureCache("edit_pack_icon")
    private val previewTextures = DynamicTextureCache("edit_icon_preview")

    override fun init() {
        val profile = ProfileManager.getProfiles().find { it.name == originalName } ?: run {
            onClose()
            return
        }
        profileForPreview = profile
        selectedPacks = profile.packIds.reversed().toMutableList()
        wasActive = ProfileManager.isActiveProfile(profile)
        recomputeAvailable()

        val centerX = width / 2
        val listHeight = listBottom - listTop

        availableList = PackList(minecraft, listWidth, listHeight, false)
        availableList.updateSizeAndPosition(listWidth, listHeight, leftColX, listTop)
        addRenderableWidget(availableList)

        selectedList = PackList(minecraft, listWidth, listHeight, true)
        selectedList.updateSizeAndPosition(listWidth, listHeight, rightColX, listTop)
        addRenderableWidget(selectedList)

        availableList.rebuild(availablePacks)
        selectedList.rebuild(selectedPacks)

        // Name field at top
        nameField = EditBox(font, centerX - 100, 16, 200, 20, Component.translatable("field.resourcepackprofiles.profile_name"))
        nameField.setMaxLength(64)
        nameField.value = originalName
        addRenderableWidget(nameField)

        // Change Icon button (stages a chosen file; applied on Done)
        addRenderableWidget(Button.builder(Component.translatable("button.resourcepackprofiles.icon")) {
            Thread {
                val path = FileDialogs.openFile(
                    "Select Profile Icon",
                    arrayOf("*.png", "*.jpg", "*.jpeg"),
                    "Image Files (*.png, *.jpg)"
                ) ?: return@Thread
                Minecraft.getInstance().execute {
                    pendingIconPath = Path.of(path)
                    pendingIconRemove = false
                    previewTextures.invalidate(PREVIEW_KEY)
                }
            }.start()
        }.bounds(centerX + 104, 16, 40, 20).build())

        // Remove Icon button (stages removal; applied on Done)
        addRenderableWidget(Button.builder(Component.literal("✕")) {
            pendingIconRemove = true
            pendingIconPath = null
            previewTextures.invalidate(PREVIEW_KEY)
        }.bounds(centerX + 148, 16, 20, 20)
            .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.remove_icon")))
            .build())

        // Save & Cancel at bottom
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onSave() }
            .bounds(centerX - 104, height - 28, 100, 20).build())
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
            .bounds(centerX + 4, height - 28, 100, 20).build())

        // Export button — trails the Done/Cancel row (blank label, icon drawn in render)
        addRenderableWidget(Button.builder(Component.literal(" ")) { onExport() }
            .bounds(centerX + 108, height - 28, 20, 20)
            .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.export")))
            .build())
    }

    private fun recomputeAvailable() {
        allKnownPackIds = minecraft.resourcePackRepository.availablePacks.map { it.id }.toSet()
        val currentSet = selectedPacks.toSet()
        availablePacks = minecraft.resourcePackRepository.availablePacks
            .filter { it.packSource.shouldAddAutomatically() && !it.isRequired }
            .map { it.id }
            .filter { it !in currentSet }
            .toMutableList()
    }

    /** Rebuilds both lists after the selected set changes (add/remove). */
    private fun rebuildLists() {
        recomputeAvailable()
        availableList.rebuild(availablePacks)
        selectedList.rebuild(selectedPacks)
        if (pendingIconRemove) previewTextures.invalidate(PREVIEW_KEY)
    }

    /** Adds a pack to the top of the selected set (top = highest priority, matching vanilla). */
    private fun addPack(packId: String) {
        selectedPacks.add(0, packId)
        rebuildLists()
    }

    private fun removePackAt(index: Int) {
        if (index !in selectedPacks.indices) return
        selectedPacks.removeAt(index)
        rebuildLists()
    }

    /** Moves the selected pack at [index] by [delta] rows, in place (preserves scroll). */
    private fun moveSelected(index: Int, delta: Int): Boolean {
        val target = index + delta
        if (index !in selectedPacks.indices || target !in selectedPacks.indices) return false
        selectedPacks[index] = selectedPacks.set(target, selectedPacks[index])
        selectedList.swapEntries(index, target)
        if (pendingIconRemove) previewTextures.invalidate(PREVIEW_KEY)
        return true
    }

    private fun isPackMissing(packId: String): Boolean = packId !in allKnownPackIds

    private fun resolvePackProfile(packId: String): Pack? =
        minecraft.resourcePackRepository.availablePacks.find { it.id == packId }

    private fun getPackIconId(packId: String): Identifier =
        packTextures.getOrRegister(packId, UNKNOWN_PACK) { PackIcons.read(packId) }

    /** Texture id for the profile-icon preview, reflecting any staged icon change. */
    private fun previewIconId(profile: ResourcePackProfile): Identifier {
        return when {
            pendingIconPath != null -> previewTextures.getOrRegister(PREVIEW_KEY, UNKNOWN_PACK) {
                try {
                    Files.newInputStream(pendingIconPath!!).use { NativeImage.read(it) }
                } catch (e: Exception) {
                    logger.error("Failed to read staged icon preview", e)
                    null
                }
            }
            pendingIconRemove -> {
                // If there are no file/ packs, buildCompositeImage returns null every time and
                // getOrRegister never caches the result, causing a re-call every frame. Short-
                // circuit to the fallback instead.
                if (selectedPacks.none { it.startsWith("file/") }) UNKNOWN_PACK
                else previewTextures.getOrRegister(PREVIEW_KEY, UNKNOWN_PACK) {
                    ProfileIconManager.buildCompositeImage(selectedPacks.reversed())
                }
            }
            else -> ProfileIconManager.getIconId(profile)
        }
    }

    private fun onExport() {
        val profile = ProfileManager.getProfiles().find { it.name == originalName } ?: return
        Thread { ProfileManager.exportProfile(profile) }.start()
    }

    private fun onSave() {
        val newName = nameField.value.trim()
        if (newName.isEmpty()) return

        val packsToSave = selectedPacks.reversed()
        val finalName: String
        if (newName != originalName) {
            if (!ProfileManager.renameProfile(originalName, newName)) return
            ProfileManager.updateProfile(newName, packsToSave)
            finalName = newName
        } else {
            ProfileManager.updateProfile(originalName, packsToSave)
            finalName = originalName
        }

        applyStagedIcon(finalName)

        // If this profile was active, re-apply it so the edits stay live instead of the profile
        // silently deactivating because its packs no longer match what's loaded.
        if (wasActive) {
            val updated = ProfileManager.getProfiles().find { it.name == finalName }
            if (updated != null && !ProfileManager.isActiveProfile(updated)) {
                ProfileManager.applyProfile(updated)
                parent.markProfileApplied()
            }
        }

        onClose()
    }

    /** Commits any staged icon change to the saved profile. */
    private fun applyStagedIcon(profileName: String) {
        try {
            when {
                pendingIconPath != null -> ProfileIconManager.importCustomIcon(profileName, pendingIconPath!!)
                pendingIconRemove -> {
                    ProfileManager.getProfiles().find { it.name == profileName }?.let {
                        ProfileIconManager.deleteCustomIcon(it)
                    }
                    ProfileManager.setCustomIcon(profileName, null)
                    ProfileIconManager.invalidate(profileName)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to apply staged icon for '$profileName'", e)
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)

        val centerX = width / 2

        // Export icon glyph on the otherwise-blank export button
        ScreenUtils.drawTransferIcon(context, centerX + 108, height - 28, into = false)

        // Profile icon preview next to the name field (reflects staged changes)
        val previewProfile = profileForPreview
        if (previewProfile != null) {
            context.blit(RenderPipelines.GUI_TEXTURED, previewIconId(previewProfile), centerX - 124, 16, 0f, 0f, 20, 20, 20, 20)
        }

        // Column headers — vanilla styles these bold + underlined + white
        val availableHeader = Component.translatable("label.resourcepackprofiles.available")
            .withStyle(ChatFormatting.BOLD, ChatFormatting.UNDERLINE, ChatFormatting.WHITE)
        context.centeredText(font, availableHeader, leftColX + listWidth / 2, listTop - 12, 0xFFFFFFFF.toInt())

        val missingCount = selectedPacks.count { isPackMissing(it) }
        val selectedHeader = (if (missingCount > 0)
            Component.translatable("label.resourcepackprofiles.selected_missing", missingCount)
        else
            Component.translatable("label.resourcepackprofiles.selected"))
            .withStyle(ChatFormatting.BOLD, ChatFormatting.UNDERLINE, if (missingCount > 0) ChatFormatting.RED else ChatFormatting.WHITE)
        context.centeredText(font, selectedHeader, rightColX + listWidth / 2, listTop - 12, 0xFFFFFFFF.toInt())
    }

    private enum class ArrowRegion { NONE, UNSELECT, MOVE_UP, MOVE_DOWN }

    /**
     * Determines which arrow region the mouse is over within the pack icon area.
     * Vanilla sprites overlay: unselect on left half, move_up on top-right, move_down on bottom-right.
     */
    private fun getHoveredArrowRegion(mouseX: Int, mouseY: Int, iconX: Int, iconY: Int): ArrowRegion {
        val relX = mouseX - iconX
        val relY = mouseY - iconY
        if (relX < 0 || relX >= packIconSize || relY < 0 || relY >= packIconSize) return ArrowRegion.NONE
        return if (relX < packIconSize / 2) {
            ArrowRegion.UNSELECT
        } else if (relY < packIconSize / 2) {
            ArrowRegion.MOVE_UP
        } else {
            ArrowRegion.MOVE_DOWN
        }
    }

    override fun onClose() {
        packTextures.cleanup()
        previewTextures.cleanup()
        minecraft.setScreen(parent)
    }

    /** One pack column. [isSelectedList] toggles add-vs-reorder behaviour and the hover affordance. */
    private inner class PackList(mc: Minecraft, w: Int, h: Int, private val isSelectedList: Boolean) :
        ObjectSelectionList<PackEntry>(mc, w, h, listTop, entryHeight) {

        override fun getRowWidth(): Int = width - 4

        // Place the scrollbar at the list's right edge, like vanilla's TransferableSelectionList
        override fun scrollBarX(): Int = x + width - scrollbarWidth()

        // Give the focused entry first crack at the key, then fall back to up/down navigation.
        override fun keyPressed(event: KeyEvent): Boolean {
            val sel = selected
            if (sel != null && sel.keyPressed(event)) return true
            return super.keyPressed(event)
        }

        fun rebuild(packIds: List<String>) =
            replaceEntries(packIds.mapIndexed { i, id -> PackEntry(id, isSelectedList, i) })

        /** Swap two entries in place (preserves scroll) and update their cached indices. */
        fun swapEntries(a: Int, b: Int) {
            children()[a].listIndex = b
            children()[b].listIndex = a
            swap(a, b)
        }
    }

    private inner class PackEntry(
        private val packId: String,
        private val isSelectedList: Boolean,
        // Current position in selectedPacks (or -1 for available-list entries). Updated by
        // PackList.swapEntries so mouse/key handlers and the hover overlay stay O(1).
        var listIndex: Int = -1
    ) : ObjectSelectionList.Entry<PackEntry>() {

        override fun getNarration(): Component =
            Component.literal(resolvePackProfile(packId)?.title?.string ?: packId)

        private fun iconX() = contentX + iconInset
        private fun iconY() = (y + height / 2) - packIconSize / 2

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            val mx = event.x().toInt()
            val my = event.y().toInt()
            val iconX = iconX()
            val iconY = iconY()

            if (!isSelectedList) {
                // Available column: click the icon to add to the selected set
                if (mx >= iconX && mx < iconX + packIconSize && my >= iconY && my < iconY + packIconSize) {
                    addPack(packId)
                    return true
                }
                return false
            }

            // Selected column: act on the hovered arrow region
            val index = listIndex
            if (index !in selectedPacks.indices) return false
            when (getHoveredArrowRegion(mx, my, iconX, iconY)) {
                ArrowRegion.UNSELECT -> {
                    removePackAt(index)
                    return true
                }
                ArrowRegion.MOVE_UP -> return moveSelected(index, -1)
                ArrowRegion.MOVE_DOWN -> return moveSelected(index, 1)
                ArrowRegion.NONE -> {}
            }
            return false
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            val confirm = event.isConfirmation || event.isSelection
            if (!isSelectedList) {
                if (confirm) {
                    addPack(packId)
                    return true
                }
                return false
            }

            val index = listIndex
            if (index !in selectedPacks.indices) return false
            if (confirm) {
                removePackAt(index)
                return true
            }
            // Shift+Up/Down reorders the focused pack (plain Up/Down navigates the list)
            if (event.hasShiftDown() && event.isUp) return moveSelected(index, -1)
            if (event.hasShiftDown() && event.isDown) return moveSelected(index, 1)
            return false
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val iconX = iconX()
            val iconY = iconY()
            val centerY = y + height / 2

            // Pack icon
            context.blit(RenderPipelines.GUI_TEXTURED, getPackIconId(packId), iconX, iconY, 0f, 0f, packIconSize, packIconSize, packIconSize, packIconSize)

            // Hover overlay: white haze + the relevant arrow sprite(s)
            if (hovered) {
                context.fill(iconX, iconY, iconX + packIconSize, iconY + packIconSize, 0x80FFFFFF.toInt())
                if (isSelectedList) {
                    val region = getHoveredArrowRegion(mouseX, mouseY, iconX, iconY)
                    val index = listIndex

                    val unselectSprite = if (region == ArrowRegion.UNSELECT) UNSELECT_HIGHLIGHTED else UNSELECT
                    context.blitSprite(RenderPipelines.GUI_TEXTURED, unselectSprite, iconX, iconY, packIconSize, packIconSize)
                    if (index > 0) {
                        val upSprite = if (region == ArrowRegion.MOVE_UP) MOVE_UP_HIGHLIGHTED else MOVE_UP
                        context.blitSprite(RenderPipelines.GUI_TEXTURED, upSprite, iconX, iconY, packIconSize, packIconSize)
                    }
                    if (index in 0 until selectedPacks.size - 1) {
                        val downSprite = if (region == ArrowRegion.MOVE_DOWN) MOVE_DOWN_HIGHLIGHTED else MOVE_DOWN
                        context.blitSprite(RenderPipelines.GUI_TEXTURED, downSprite, iconX, iconY, packIconSize, packIconSize)
                    }
                } else {
                    val overIcon = mouseX >= iconX && mouseX < iconX + packIconSize && mouseY >= iconY && mouseY < iconY + packIconSize
                    val selectSprite = if (overIcon) SELECT_HIGHLIGHTED else SELECT
                    context.blitSprite(RenderPipelines.GUI_TEXTURED, selectSprite, iconX, iconY, packIconSize, packIconSize)
                }
            }

            // Pack name and description
            val packProfile = resolvePackProfile(packId)
            val missing = isSelectedList && isPackMissing(packId)
            val displayName = packProfile?.title?.string ?: packId
            val textX = iconX + packIconSize + 4
            val maxTextWidth = contentRight - iconInset - textX
            val nameColor = if (missing) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt()
            context.text(font, Component.literal(ScreenUtils.truncate(font, displayName, maxTextWidth)), textX, centerY - 9, nameColor, true)

            val description = if (missing) Component.translatable("label.resourcepackprofiles.pack_missing").string else packProfile?.description?.string ?: ""
            if (description.isNotEmpty()) {
                val descColor = if (missing) 0xFFFF5555.toInt() else 0xFF808080.toInt()
                context.text(font, Component.literal(ScreenUtils.truncate(font, description, maxTextWidth)), textX, centerY + 1, descColor, false)
            }
        }
    }

    private companion object {
        const val PREVIEW_KEY = "staged"
    }
}
