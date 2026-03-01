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

            val buttonY = y + 1

            val starLabel = if (profile.favorite) "\u2605" else "\u2606"
            addDrawableChild(ButtonWidget.builder(Text.literal(starLabel)) {
                ProfileManager.toggleFavorite(profile.name)
                rebuildProfileButtons()
            }.dimensions(width / 2 + 38, buttonY, 20, 20).build())

            addDrawableChild(ButtonWidget.builder(Text.literal("Edit")) { client?.setScreen(EditProfileScreen(this, profile.name)) }
                .dimensions(width / 2 + 60, buttonY, 40, 20)
                .build())

            addDrawableChild(ButtonWidget.builder(Text.literal("\uD83D\uDDD1")) { onDelete(profile.name) }
                .dimensions(width / 2 + 104, buttonY, 20, 20)
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
        if (ProfileManager.isActiveProfile(profile)) return
        client?.setScreen(ConfirmScreen(
            { confirmed ->
                if (confirmed) {
                    val missingIds = ProfileManager.applyProfile(profile)
                    if (missingIds.isNotEmpty()) {
                        val missingList = missingIds.joinToString("\n") { "• $it" }
                        val parentScreen = this
                        client?.setScreen(object : Screen(Text.literal("Missing Packs")) {
                            override fun init() {
                                addDrawableChild(ButtonWidget.builder(Text.literal("OK")) { client?.setScreen(parentScreen) }
                                    .dimensions(width / 2 - 50, height / 2 + 40, 100, 20)
                                    .build())
                            }
                            override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
                                super.render(context, mouseX, mouseY, delta)
                                context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 40, 0xFFFFFF or (0xFF shl 24))
                                val lines = missingList.split("\n")
                                for ((i, line) in lines.withIndex()) {
                                    context.drawCenteredTextWithShadow(textRenderer, Text.literal(line), width / 2, height / 2 - 20 + i * 12, 0xFFAAAAAA.toInt())
                                }
                            }
                        })
                        return@ConfirmScreen
                    }
                }
                client?.setScreen(this)
            },
            Text.literal("Load Profile"),
            Text.literal("Load profile '${profile.name}'? This will change your active resource packs.")
        ))
    }

    private fun onDelete(name: String) {
        client?.setScreen(ConfirmScreen(
            { confirmed ->
                if (confirmed) {
                    ProfileManager.deleteProfile(name)
                    scrollOffset = 0
                    rebuildProfileButtons()
                }
                client?.setScreen(this)
            },
            Text.literal("Delete Profile"),
            Text.literal("Are you sure you want to delete profile '$name'?")
        ))
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
        if (textRenderer.getWidth(text) <= maxWidth) return text
        var s = text
        while (textRenderer.getWidth("$s...") > maxWidth && s.isNotEmpty()) {
            s = s.dropLast(1)
        }
        return "$s..."
    }

    private fun getMissingPackCount(profile: ResourcePackProfile): Int {
        val allIds = client!!.resourcePackManager.profiles.map { it.id }.toSet()
        return profile.packIds.count { it !in allIds }
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
            val iconY = y + 1

            // Active profile outline
            val isActive = ProfileManager.isActiveProfile(profile)
            if (isActive) {
                val outlineColor = 0xAAAAAA or (0xFF shl 24)
                val left = iconX - 2
                val top = y - 2
                val right = width / 2 + 126
                val bottom = y + entryHeight - 2
                context.fill(left, top, right, top + 1, outlineColor)         // top
                context.fill(left, bottom, right, bottom + 1, outlineColor)   // bottom
                context.fill(left, top, left + 1, bottom + 1, outlineColor)   // left
                context.fill(right - 1, top, right, bottom + 1, outlineColor) // right
            }

            // Draw icon
            val iconId = ProfileIconManager.getIconId(profile)
            context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, iconX, iconY, 0f, 0f, iconSize, iconSize, iconSize, iconSize)

            // Draw name label shifted right to make room for icon — highlight on hover
            val nameX = iconX + iconSize + 4
            val maxTextWidth = width / 2 + 34 - nameX
            val label = truncateText(getProfileLabel(profile), maxTextWidth)
            val nameWidth = textRenderer.getWidth(label)
            val isHoveringName = mouseX >= nameX && mouseX < nameX + nameWidth && mouseY >= y && mouseY < y + entryHeight
            val nameColor = if (isHoveringName) 0xFFFF55 or (0xFF shl 24) else 0xFFFFFF or (0xFF shl 24)
            context.drawText(textRenderer, Text.literal(label), nameX, y + 2, nameColor, true)

            // Pack count
            val missingCount = getMissingPackCount(profile)
            val subLabel = truncateText(getProfileSubLabel(profile), maxTextWidth)
            val subColor = if (missingCount > 0) 0xFF5555 or (0xFF shl 24) else 0xAAAAAA or (0xFF shl 24)
            context.drawText(textRenderer, Text.literal(subLabel), nameX, y + 14, subColor, false)
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
            val nameX = iconX + iconSize + 4
            val maxTextWidth = width / 2 + 34 - nameX
            val label = truncateText(getProfileLabel(profile), maxTextWidth)
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
