package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import org.lwjgl.BufferUtils
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.tinyfd.TinyFileDialogs
import org.zephbyte.resourcepackprofiles.client.RefreshablePackScreen
import org.zephbyte.resourcepackprofiles.client.profile.ProfileIconManager
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.profile.ResourcePackProfile
import java.nio.file.Path

class ProfileScreen(private val parent: Screen?) : Screen(Component.literal("Resource Pack Profiles")) {

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

    override fun init() {
        listBottom = height - 56
        maxVisibleEntries = (listBottom - listTop) / entryHeight

        nameField = EditBox(font, width / 2 - 152, height - 52, 200, 20, Component.literal("Profile Name"))
        nameField.setMaxLength(64)
        nameField.setHint(Component.literal("Profile name..."))
        addRenderableWidget(nameField)

        addRenderableWidget(Button.builder(Component.literal("Save Current")) { onSave() }
            .bounds(width / 2 + 52, height - 52, 100, 20)
            .build())

        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }
            .bounds(width / 2 - 50, height - 28, 100, 20)
            .build())

        addRenderableWidget(Button.builder(Component.literal(" ")) { onImport() }
            .bounds(width / 2 + 54, height - 28, 20, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Import Profile")))
            .build())

        rebuildProfileButtons()
    }

    private fun rebuildProfileButtons() {
        // Remove old profile buttons by clearing and re-adding fixed widgets
        clearWidgets()

        addRenderableWidget(nameField)

        addRenderableWidget(Button.builder(Component.literal("Save Current")) { onSave() }
            .bounds(width / 2 + 52, height - 52, 100, 20)
            .build())

        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }
            .bounds(width / 2 - 50, height - 28, 100, 20)
            .build())

        addRenderableWidget(Button.builder(Component.literal(" ")) { onImport() }
            .bounds(width / 2 + 54, height - 28, 20, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Import Profile")))
            .build())

        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight

            val buttonY = y + 1

            val starLabel = if (profile.favorite) "★" else "☆"
            addRenderableWidget(Button.builder(Component.literal(starLabel)) {
                ProfileManager.toggleFavorite(profile.name)
                rebuildProfileButtons()
            }.bounds(width / 2 + 62, buttonY, 20, 20).build())

            addRenderableWidget(Button.builder(Component.literal("✎")) { minecraft?.setScreen(EditProfileScreen(this, profile.name)) }
                .bounds(width / 2 + 84, buttonY, 20, 20)
                .build())

            addRenderableWidget(Button.builder(Component.literal("✕")) { onDelete(profile.name) }
                .bounds(width / 2 + 106, buttonY, 20, 20)
                .build())
        }
    }

    private fun onSave() {
        val name = nameField.value.trim()
        if (name.isEmpty()) return

        if (ProfileManager.hasProfile(name)) {
            minecraft?.setScreen(ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        saveProfile(name)
                    }
                    minecraft?.setScreen(this)
                },
                Component.literal("Overwrite Profile"),
                Component.literal("A profile named '$name' already exists. Overwrite it?")
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
                        val missingList = missingIds.joinToString("\n") { "• $it" }
                        val parentScreen = this
                        minecraft?.setScreen(object : Screen(Component.literal("Missing Packs")) {
                            override fun init() {
                                addRenderableWidget(Button.builder(Component.literal("OK")) { minecraft?.setScreen(parentScreen) }
                                    .bounds(width / 2 - 50, height / 2 + 40, 100, 20)
                                    .build())
                            }
                            override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
                                super.extractRenderState(context, mouseX, mouseY, delta)
                                context.centeredText(font, title, width / 2, height / 2 - 40, 0xFFFFFF or (0xFF shl 24))
                                val lines = missingList.split("\n")
                                for ((i, line) in lines.withIndex()) {
                                    context.centeredText(font, Component.literal(line), width / 2, height / 2 - 20 + i * 12, 0xFFAAAAAA.toInt())
                                }
                            }
                        })
                        return@ConfirmScreen
                    }
                }
                minecraft?.setScreen(this)
            },
            Component.literal("Load Profile"),
            Component.literal("Load profile '${profile.name}'? This will change your active resource packs.")
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
            Component.literal("Delete Profile"),
            Component.literal("Are you sure you want to delete profile '$name'?")
        ))
    }

    private fun onImport() {
        Thread {
            val filterPatterns = arrayOf("*.rpprofile")
            val filterBuf = BufferUtils.createPointerBuffer(filterPatterns.size)
            for (pattern in filterPatterns) {
                filterBuf.put(MemoryUtil.memUTF8(pattern))
            }
            filterBuf.flip()

            val pathStr = TinyFileDialogs.tinyfd_openFileDialog(
                "Import Profile",
                null,
                filterBuf,
                "Resource Pack Profile (*.rpprofile)",
                false
            ) ?: return@Thread

            val filePath = Path.of(pathStr)
            Minecraft.getInstance().execute {
                val result = ProfileManager.importProfileFromPath(filePath)
                if (result == null) return@execute
                if (result.startsWith("!")) {
                    val name = result.substring(1)
                    minecraft?.setScreen(ConfirmScreen(
                        { confirmed ->
                            if (confirmed) {
                                ProfileManager.importProfileFromPath(filePath, name)
                                scrollOffset = 0
                                rebuildProfileButtons()
                            }
                            minecraft?.setScreen(this)
                        },
                        Component.literal("Overwrite Profile"),
                        Component.literal("A profile named '$name' already exists. Overwrite it?")
                    ))
                } else {
                    scrollOffset = 0
                    rebuildProfileButtons()
                }
            }
        }.start()
    }

    private fun getProfileLabel(profile: ResourcePackProfile): String {
        return profile.name
    }

    private fun getProfileSubLabel(profile: ResourcePackProfile): String {
        val missingCount = getMissingPackCount(profile)
        return if (missingCount > 0) {
            "${profile.packIds.size} packs | $missingCount missing"
        } else {
            "${profile.packIds.size} packs"
        }
    }

    private fun truncateText(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var s = text
        while (font.width("$s...") > maxWidth && s.isNotEmpty()) {
            s = s.dropLast(1)
        }
        return "$s..."
    }

    private fun getMissingPackCount(profile: ResourcePackProfile): Int {
        val allIds = minecraft!!.resourcePackRepository.availablePacks.map { it.id }.toSet()
        return profile.packIds.count { it !in allIds }
    }

    private fun drawImportIcon(context: GuiGraphicsExtractor, bx: Int, by: Int) {
        val white = 0xFFFFFFFF.toInt()
        // Box: open-top rectangle (bottom + left + right sides)
        context.fill(bx + 4, by + 15, bx + 16, by + 16, white)  // bottom
        context.fill(bx + 4, by + 10, bx + 5, by + 16, white)   // left
        context.fill(bx + 15, by + 10, bx + 16, by + 16, white) // right
        // Arrow shaft: extends past chevron to form sharp point
        context.fill(bx + 9, by + 5, bx + 11, by + 14, white)
        // Arrow head: chevron pointing down
        context.fill(bx + 7, by + 11, bx + 9, by + 12, white)   // left wing
        context.fill(bx + 11, by + 11, bx + 13, by + 12, white) // right wing
        context.fill(bx + 8, by + 12, bx + 9, by + 13, white)   // left tip
        context.fill(bx + 11, by + 12, bx + 12, by + 13, white) // right tip
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)

        // Draw import icon on the import button
        drawImportIcon(context, width / 2 + 54, height - 28)

        // Title
        context.centeredText(font, title, width / 2, 16, 0xFFFFFF or (0xFF shl 24))

        // Profile list with icons
        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight
            val iconX = width / 2 - 150
            val iconY = y + 1

            // Active profile outline
            val isActive = ProfileManager.isActiveProfile(profile)
            if (isActive) {
                val outlineColor = 0xAAAAAA or (0xFF shl 24)
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
            val nameX = iconX + iconSize + 4
            val maxTextWidth = width / 2 + 52 - nameX
            val label = truncateText(getProfileLabel(profile), maxTextWidth)
            val nameWidth = font.width(label)
            val isHoveringName = mouseX >= nameX && mouseX < nameX + nameWidth && mouseY >= y && mouseY < y + entryHeight
            val nameColor = if (isHoveringName) 0xFFFF55 or (0xFF shl 24) else 0xFFFFFF or (0xFF shl 24)
            context.text(font, Component.literal(label), nameX, y + 2, nameColor, true)

            // Pack count
            val missingCount = getMissingPackCount(profile)
            val subLabel = truncateText(getProfileSubLabel(profile), maxTextWidth)
            val subColor = if (missingCount > 0) 0xFF5555 or (0xFF shl 24) else 0xAAAAAA or (0xFF shl 24)
            context.text(font, Component.literal(subLabel), nameX, y + 14, subColor, false)

            // Red tint on trash button when hovered
            val buttonY = y + 1
            val trashX = width / 2 + 106
            val trashHovered = mouseX >= trashX && mouseX < trashX + 20 && mouseY >= buttonY && mouseY < buttonY + 20
            if (trashHovered) {
                context.fill(trashX, buttonY, trashX + 20, buttonY + 20, 0x60FF0000)
            }
        }

        if (profiles.isEmpty()) {
            context.centeredText(
                font,
                Component.literal("No profiles saved"),
                width / 2,
                listTop + 20,
                0xAAAAAA or (0xFF shl 24)
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

        // Check if click is on an icon area
        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight
            val iconX = width / 2 - 150

            // Click on profile name → load profile
            val nameX = iconX + iconSize + 4
            val maxTextWidth = width / 2 + 52 - nameX
            val label = truncateText(getProfileLabel(profile), maxTextWidth)
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
