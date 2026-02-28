package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.profile.ResourcePackProfile

class ProfileScreen(private val parent: Screen?) : Screen(Text.literal("Resource Pack Profiles")) {

    private lateinit var nameField: TextFieldWidget
    private var statusMessage: Text = Text.empty()
    private var statusTicks = 0
    private var scrollOffset = 0
    private val entryHeight = 24
    private val listTop = 32
    private var listBottom = 0
    private var maxVisibleEntries = 0

    override fun init() {
        listBottom = height - 80
        maxVisibleEntries = (listBottom - listTop) / entryHeight

        nameField = TextFieldWidget(textRenderer, width / 2 - 152, height - 68, 200, 20, Text.literal("Profile Name"))
        nameField.setMaxLength(64)
        nameField.setPlaceholder(Text.literal("Profile name..."))
        addDrawableChild(nameField)

        addDrawableChild(ButtonWidget.builder(Text.literal("Save Current")) { onSave() }
            .dimensions(width / 2 + 52, height - 68, 100, 20)
            .build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Done")) { close() }
            .dimensions(width / 2 - 50, height - 40, 100, 20)
            .build())

        rebuildProfileButtons()
    }

    private fun rebuildProfileButtons() {
        // Remove old profile buttons by clearing and re-adding fixed widgets
        clearChildren()

        addDrawableChild(nameField)

        addDrawableChild(ButtonWidget.builder(Text.literal("Save Current")) { onSave() }
            .dimensions(width / 2 + 52, height - 68, 100, 20)
            .build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Done")) { close() }
            .dimensions(width / 2 - 50, height - 40, 100, 20)
            .build())

        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight

            addDrawableChild(ButtonWidget.builder(Text.literal("Load")) { onLoad(profile) }
                .dimensions(width / 2 + 60, y, 40, 20)
                .build())

            addDrawableChild(ButtonWidget.builder(Text.literal("X")) { onDelete(profile.name) }
                .dimensions(width / 2 + 104, y, 20, 20)
                .build())
        }
    }

    private fun onSave() {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            setStatus(Text.literal("Enter a profile name"))
            return
        }
        ProfileManager.saveCurrentAsProfile(name)
        nameField.text = ""
        scrollOffset = 0
        rebuildProfileButtons()
        setStatus(Text.literal("Saved profile: $name"))
    }

    private fun onLoad(profile: ResourcePackProfile) {
        val missing = ProfileManager.applyProfile(profile)
        if (missing.isEmpty()) {
            setStatus(Text.literal("Loaded profile: ${profile.name}"))
        } else {
            setStatus(Text.literal("Loaded with ${missing.size} missing pack(s)"))
        }
    }

    private fun onDelete(name: String) {
        ProfileManager.deleteProfile(name)
        scrollOffset = 0
        rebuildProfileButtons()
        setStatus(Text.literal("Deleted profile: $name"))
    }

    private fun setStatus(message: Text) {
        statusMessage = message
        statusTicks = 60
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF)

        val profiles = ProfileManager.getProfiles()
        val visibleProfiles = profiles.drop(scrollOffset).take(maxVisibleEntries)

        for ((index, profile) in visibleProfiles.withIndex()) {
            val y = listTop + index * entryHeight
            val label = "${profile.name} (${profile.packIds.size} packs)"
            context.drawTextWithShadow(textRenderer, label, width / 2 - 152, y + 6, 0xFFFFFF)
        }

        if (profiles.isEmpty()) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("No profiles saved"),
                width / 2,
                listTop + 20,
                0x888888
            )
        }

        if (statusTicks > 0) {
            context.drawCenteredTextWithShadow(textRenderer, statusMessage, width / 2, height - 52, 0x55FF55)
        }
    }

    override fun tick() {
        super.tick()
        if (statusTicks > 0) statusTicks--
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val profiles = ProfileManager.getProfiles()
        val maxScroll = (profiles.size - maxVisibleEntries).coerceAtLeast(0)
        scrollOffset = (scrollOffset - verticalAmount.toInt()).coerceIn(0, maxScroll)
        rebuildProfileButtons()
        return true
    }

    override fun close() {
        client?.setScreen(parent)
    }
}
