package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ConfirmScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.zephbyte.resourcepackprofiles.client.profile.ProfileIconManager
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.profile.ResourcePackProfile

class ProfileScreen(private val parent: Screen?) : Screen(Text.literal("Resource Pack Profiles")) {

    private lateinit var nameField: TextFieldWidget
    private var scrollOffset = 0
    private val entryHeight = 26
    private val listTop = 32
    private var listBottom = 0
    private var maxVisibleEntries = 0
    private val iconSize = 20

    override fun init() {
        listBottom = height - 52
        maxVisibleEntries = (listBottom - listTop) / entryHeight

        nameField = TextFieldWidget(textRenderer, width / 2 - 152, height - 48, 200, 20, Text.literal("Profile Name"))
        nameField.setMaxLength(64)
        nameField.setPlaceholder(Text.literal("Profile name..."))
        addDrawableChild(nameField)

        addDrawableChild(ButtonWidget.builder(Text.literal("Save Current")) { onSave() }
            .dimensions(width / 2 + 52, height - 48, 100, 20)
            .build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Done")) { close() }
            .dimensions(width / 2 - 50, height - 24, 100, 20)
            .build())

        rebuildProfileButtons()
    }

    private fun rebuildProfileButtons() {
        // Remove old profile buttons by clearing and re-adding fixed widgets
        clearChildren()

        addDrawableChild(nameField)

        addDrawableChild(ButtonWidget.builder(Text.literal("Save Current")) { onSave() }
            .dimensions(width / 2 + 52, height - 48, 100, 20)
            .build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Done")) { close() }
            .dimensions(width / 2 - 50, height - 24, 100, 20)
            .build())

        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight

            addDrawableChild(ButtonWidget.builder(Text.literal("Edit")) { client?.setScreen(EditProfileScreen(this, profile.name)) }
                .dimensions(width / 2 + 60, y, 40, 20)
                .build())

            addDrawableChild(ButtonWidget.builder(Text.literal("\uD83D\uDDD1")) { onDelete(profile.name) }
                .dimensions(width / 2 + 104, y, 20, 20)
                .build())
        }
    }

    private fun onSave() {
        val name = nameField.text.trim()
        if (name.isEmpty()) return

        if (ProfileManager.hasProfile(name)) {
            client?.setScreen(ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        saveProfile(name)
                    }
                    client?.setScreen(this)
                },
                Text.literal("Overwrite Profile"),
                Text.literal("A profile named '$name' already exists. Overwrite it?")
            ))
        } else {
            saveProfile(name)
        }
    }

    private fun saveProfile(name: String) {
        ProfileManager.saveCurrentAsProfile(name)
        nameField.text = ""
        scrollOffset = 0
        rebuildProfileButtons()
    }

    private fun onLoad(profile: ResourcePackProfile) {
        ProfileManager.applyProfile(profile)
    }

    private fun onDelete(name: String) {
        ProfileManager.deleteProfile(name)
        scrollOffset = 0
        rebuildProfileButtons()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // Title
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF or (0xFF shl 24))

        // Profile list with icons
        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight
            val iconX = width / 2 - 150
            val iconY = y

            // Draw icon
            val iconId = ProfileIconManager.getIconId(profile)
            context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, iconX, iconY, 0f, 0f, iconSize, iconSize, iconSize, iconSize)

            // Draw name label shifted right to make room for icon — highlight on hover
            val isActive = ProfileManager.isActiveProfile(profile)
            val suffix = if (isActive) " (Active)" else " (${profile.packIds.size} packs)"
            val label = "${profile.name}$suffix"
            val nameX = iconX + iconSize + 4
            val nameWidth = textRenderer.getWidth(label)
            val isHoveringName = mouseX >= nameX && mouseX < nameX + nameWidth && mouseY >= y && mouseY < y + entryHeight
            val nameColor = when {
                isHoveringName -> 0xFFFF55 or (0xFF shl 24)
                isActive -> 0x55FF55 or (0xFF shl 24)
                else -> 0xFFFFFF or (0xFF shl 24)
            }
            context.drawText(textRenderer, Text.literal(label), nameX, y + 5, nameColor, true)
        }

        if (profiles.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("No profiles saved"),
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
            val barX = width / 2 + 128
            context.fill(barX, barY, barX + 3, barY + barHeight, 0x80FFFFFF.toInt())
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        val mouseX = click.x()
        val mouseY = click.y()

        // Check if click is on an icon area
        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight
            val iconX = width / 2 - 150

            // Click on profile name → load profile
            val isActive = ProfileManager.isActiveProfile(profile)
            val suffix = if (isActive) " (Active)" else " (${profile.packIds.size} packs)"
            val label = "${profile.name}$suffix"
            val nameX = iconX + iconSize + 4
            val nameWidth = textRenderer.getWidth(label)
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

    override fun close() {
        ProfileIconManager.cleanup()
        client?.setScreen(parent)
    }
}
