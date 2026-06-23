package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
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
    private lateinit var profileList: ProfileList

    // Tracks whether a profile was applied this session, so onClose can return to a refreshed
    // pack selection screen instead of a stale one that would overwrite the applied packs.
    private var profileApplied = false

    // Computed once per frame in extractRenderState and shared by all ProfileEntry.extractContent
    // calls in that frame so each row doesn't rebuild the same set independently.
    private var cachedAvailablePackIds: Set<String> = emptySet()

    private val listTop = 32
    private val entryHeight = 26
    private val iconSize = 21
    private val rowButtonSize = 20
    private val ROW_PADDING = 6

    private val listBottom get() = height - 56

    override fun init() {
        profileList = ProfileList(minecraft)
        addRenderableWidget(profileList)

        nameField = EditBox(font, width / 2 - 152, height - 52, 200, 20, Component.translatable("field.resourcepackprofiles.profile_name"))
        nameField.setMaxLength(64)
        nameField.setHint(Component.translatable("field.resourcepackprofiles.profile_name.hint"))
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

        profileList.refresh()
    }

    private fun onSave() {
        val name = nameField.value.trim()
        if (name.isEmpty()) return

        if (ProfileManager.hasProfile(name)) {
            minecraft.setScreenAndShow(ConfirmScreen(
                { confirmed ->
                    if (confirmed) saveProfile(name)
                    minecraft.setScreenAndShow(this)
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
        profileList.refresh()
    }

    /**
     * Marks that packs were applied (e.g. by the edit screen re-applying an active profile), so
     * [onClose] returns to a refreshed pack screen rather than a stale snapshot.
     */
    fun markProfileApplied() {
        profileApplied = true
    }

    private fun onLoad(profile: ResourcePackProfile) {
        if (ProfileManager.isActiveProfile(profile)) return
        minecraft.setScreenAndShow(ConfirmScreen(
            { confirmed ->
                if (confirmed) {
                    val missingIds = ProfileManager.applyProfile(profile)
                    profileApplied = true
                    if (missingIds.isNotEmpty()) {
                        minecraft.setScreenAndShow(MissingPacksScreen(this, missingIds))
                        return@ConfirmScreen
                    }
                }
                minecraft.setScreenAndShow(this)
            },
            Component.translatable("screen.resourcepackprofiles.load.title"),
            Component.translatable("screen.resourcepackprofiles.load.message", profile.name)
        ))
    }

    private fun onDelete(name: String) {
        minecraft.setScreenAndShow(ConfirmScreen(
            { confirmed ->
                if (confirmed) {
                    ProfileManager.deleteProfile(name)
                    profileList.refresh()
                }
                minecraft.setScreenAndShow(this)
            },
            Component.translatable("screen.resourcepackprofiles.delete.title"),
            Component.translatable("screen.resourcepackprofiles.delete.message", name)
        ))
    }

    private fun onImport() {
        Thread {
            val pathStr = ProfileManager.pickProfileToImport() ?: return@Thread
            val filePath = Path.of(pathStr)
            // The blocking dialog may outlive this screen if the user closes it first; only act if
            // we're still the active screen, otherwise handleImport would yank a closed screen back.
            Minecraft.getInstance().execute { if (minecraft.gui.screen() === this) handleImport(filePath) }
        }.start()
    }

    private fun handleImport(filePath: Path, overwriteName: String? = null) {
        when (val result = ProfileManager.importProfileFromPath(filePath, overwriteName)) {
            is ImportResult.Conflict -> minecraft.setScreenAndShow(ConfirmScreen(
                { confirmed ->
                    if (confirmed) handleImport(filePath, result.name)
                    else minecraft.setScreenAndShow(this)
                },
                Component.translatable("screen.resourcepackprofiles.overwrite.title"),
                Component.translatable("screen.resourcepackprofiles.overwrite.message", result.name)
            ))
            is ImportResult.Imported -> {
                profileList.refresh()
                minecraft.setScreenAndShow(this)
            }
            ImportResult.Failed -> minecraft.setScreenAndShow(this)
        }
    }

    private fun missingPackCount(profile: ResourcePackProfile): Int =
        profile.packIds.count { it !in cachedAvailablePackIds }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // Rebuild the available-ID set once per frame before the list entries render.
        cachedAvailablePackIds = minecraft.resourcePackRepository.availablePacks.map { it.id }.toSet()
        super.extractRenderState(context, mouseX, mouseY, delta)

        // Icon glyph on the otherwise-blank import button
        ScreenUtils.drawTransferIcon(context, width / 2 + 54, height - 28, into = true)

        context.centeredText(font, title, width / 2, 16, 0xFFFFFFFF.toInt())

        if (profileList.children().isEmpty()) {
            context.centeredText(
                font,
                Component.translatable("label.resourcepackprofiles.no_profiles"),
                width / 2,
                listTop + 20,
                0xFFAAAAAA.toInt()
            )
        }
    }

    override fun onClose() {
        ProfileIconManager.cleanup()
        val returnTo = parent
        // If we came from the vanilla pack selection screen and applied a profile, its model holds
        // a stale snapshot that would wipe the applied packs on close. Return to a refreshed
        // instance whose model reflects the packs we just applied instead.
        if (profileApplied && returnTo is RefreshablePackScreen) {
            minecraft.setScreenAndShow(returnTo.rpp_createRefreshedScreen())
        } else {
            minecraft.gui.setScreen(returnTo)
        }
    }

    /** Scrolling, focusable list of saved profiles. */
    private inner class ProfileList(mc: Minecraft) :
        ContainerObjectSelectionList<ProfileEntry>(mc, this@ProfileScreen.width, listBottom - listTop, listTop, entryHeight) {

        override fun getRowWidth(): Int = 300

        fun refresh() {
            replaceEntries(ProfileManager.getProfiles().map { ProfileEntry(it) })
        }
    }

    /** A single profile row: icon, name, pack count, and favorite/edit/delete buttons. */
    private inner class ProfileEntry(private val profile: ResourcePackProfile) :
        ContainerObjectSelectionList.Entry<ProfileEntry>() {

        private val starButton = Button.builder(Component.literal(if (profile.favorite) "★" else "☆")) {
            ProfileManager.toggleFavorite(profile.name)
            profileList.refresh()
        }.bounds(0, 0, rowButtonSize, rowButtonSize)
            .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.favorite")))
            .build()

        private val editButton = Button.builder(Component.literal("✎")) {
            minecraft.setScreenAndShow(EditProfileScreen(this@ProfileScreen, profile.name))
        }.bounds(0, 0, rowButtonSize, rowButtonSize)
            .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.edit")))
            .build()

        private val deleteButton = Button.builder(Component.literal("✕")) {
            onDelete(profile.name)
        }.bounds(0, 0, rowButtonSize, rowButtonSize)
            .tooltip(Tooltip.create(Component.translatable("tooltip.resourcepackprofiles.delete")))
            .build()

        private val buttons = listOf(starButton, editButton, deleteButton)

        override fun children(): List<GuiEventListener> = buttons
        override fun narratables(): List<NarratableEntry> = buttons

        override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
            // Let the row buttons claim the click first; otherwise the row itself loads the profile.
            if (super.mouseClicked(event, doubled)) return true
            onLoad(profile)
            return true
        }

        override fun extractContent(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, delta: Float) {
            val centerY = y + height / 2
            // Inset content from the row edges so the icon/buttons don't touch the highlight border.
            val leftEdge = contentX + ROW_PADDING
            val rightEdge = contentRight - ROW_PADDING

            // Position the row buttons against the (padded) right edge, vertically centered
            val btnY = centerY - rowButtonSize / 2
            deleteButton.setPosition(rightEdge - rowButtonSize, btnY)
            editButton.setPosition(deleteButton.x - rowButtonSize - 2, btnY)
            starButton.setPosition(editButton.x - rowButtonSize - 2, btnY)

            // Active-profile outline
            if (ProfileManager.isActiveProfile(profile)) {
                val outline = 0xFFAAAAAA.toInt()
                val left = leftEdge - 2
                val top = centerY - height / 2 + 1
                val right = rightEdge + 2
                val bottom = centerY + height / 2 - 1
                context.fill(left, top, right, top + 1, outline)
                context.fill(left, bottom, right, bottom + 1, outline)
                context.fill(left, top, left + 1, bottom + 1, outline)
                context.fill(right - 1, top, right, bottom + 1, outline)
            }

            // Profile icon, vertically centered
            val iconY = centerY - iconSize / 2
            context.blit(RenderPipelines.GUI_TEXTURED, ProfileIconManager.getIconId(profile), leftEdge, iconY, 0f, 0f, iconSize, iconSize, iconSize, iconSize)

            // Name + pack count, two lines centered around the row middle
            val nameX = leftEdge + iconSize + 4
            val maxTextWidth = starButton.x - 4 - nameX
            val nameColor = if (hovered) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt()
            context.text(font, Component.literal(ScreenUtils.truncate(font, profile.name, maxTextWidth)), nameX, centerY - 9, nameColor, true)

            val missing = missingPackCount(profile)
            val subLabel = if (missing > 0)
                Component.translatable("label.resourcepackprofiles.pack_count_missing", profile.packIds.size, missing)
            else
                Component.translatable("label.resourcepackprofiles.pack_count", profile.packIds.size)
            val subColor = if (missing > 0) 0xFFFF5555.toInt() else 0xFFAAAAAA.toInt()
            context.text(font, subLabel, nameX, centerY + 1, subColor, false)

            // Buttons on top
            buttons.forEach { it.extractRenderState(context, mouseX, mouseY, delta) }
        }
    }
}
