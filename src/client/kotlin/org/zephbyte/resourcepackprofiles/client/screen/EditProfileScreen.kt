package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.texture.NativeImage
import net.minecraft.client.texture.NativeImageBackedTexture
import net.minecraft.resource.ResourcePackProfile
import net.minecraft.resource.ResourcePackSource
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.zephbyte.resourcepackprofiles.client.profile.ProfileIconManager
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager

class EditProfileScreen(
    private val parent: ProfileScreen,
    private val originalName: String
) : Screen(Text.literal("Edit Profile")) {

    private lateinit var nameField: TextFieldWidget

    // Editing state — pack IDs
    private var selectedPacks = mutableListOf<String>()
    private var availablePacks = mutableListOf<String>()

    // Scroll state (pixel-based for smooth scrolling)
    private var availableScrollY = 0.0
    private var selectedScrollY = 0.0

    // Layout constants
    private val entryHeight = 36
    private val packIconSize = 32
    private val listTop = 48
    private var listBottom = 0
    private val listPadding = 4
    private val scrollSpeed = 12.0

    // Vanilla arrow sprite IDs
    private val SELECT_HIGHLIGHTED = Identifier.ofVanilla("transferable_list/select_highlighted")
    private val SELECT = Identifier.ofVanilla("transferable_list/select")
    private val UNSELECT_HIGHLIGHTED = Identifier.ofVanilla("transferable_list/unselect_highlighted")
    private val UNSELECT = Identifier.ofVanilla("transferable_list/unselect")
    private val MOVE_UP_HIGHLIGHTED = Identifier.ofVanilla("transferable_list/move_up_highlighted")
    private val MOVE_UP = Identifier.ofVanilla("transferable_list/move_up")
    private val MOVE_DOWN_HIGHLIGHTED = Identifier.ofVanilla("transferable_list/move_down_highlighted")
    private val MOVE_DOWN = Identifier.ofVanilla("transferable_list/move_down")
    private val ARROW_SIZE = 32

    // Pack icon cache
    private val packIconCache = mutableMapOf<String, Identifier>()
    private val registeredPackTextures = mutableSetOf<Identifier>()

    override fun init() {
        listBottom = height - 56

        val profile = ProfileManager.getProfiles().find { it.name == originalName } ?: run {
            close()
            return
        }
        selectedPacks = profile.packIds.toMutableList()
        recomputeAvailable()

        val centerX = width / 2

        // Name field at top
        nameField = TextFieldWidget(textRenderer, centerX - 100, 16, 200, 20, Text.literal("Profile Name"))
        nameField.setMaxLength(64)
        nameField.text = originalName
        addDrawableChild(nameField)

        // Change Icon button
        addDrawableChild(ButtonWidget.builder(Text.literal("Icon...")) {
            Thread {
                val success = ProfileIconManager.openFilePickerAndImport(originalName)
                if (success) {
                    client?.execute { }
                }
            }.start()
        }.dimensions(centerX + 104, 16, 40, 20).build())

        // Save & Cancel at bottom
        addDrawableChild(ButtonWidget.builder(Text.literal("Done")) { onSave() }
            .dimensions(centerX - 104, height - 28, 100, 20).build())
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel")) { close() }
            .dimensions(centerX + 4, height - 28, 100, 20).build())
    }

    private fun recomputeAvailable() {
        val client = client ?: return
        val currentSet = selectedPacks.toSet()
        availablePacks = client.resourcePackManager.profiles
            .filter { it.getSource().canBeEnabledLater() }
            .map { it.id }
            .filter { it !in currentSet }
            .toMutableList()
    }

    private fun resolvePackProfile(packId: String): ResourcePackProfile? {
        val client = client ?: return null
        return client.resourcePackManager.profiles.find { it.id == packId }
    }

    private fun getPackIconId(packId: String): Identifier {
        packIconCache[packId]?.let { return it }

        val packProfile = resolvePackProfile(packId)
        if (packProfile != null) {
            try {
                val pack = packProfile.createResourcePack()
                if (pack != null) {
                    val iconSupplier = pack.openRoot("pack.png")
                    if (iconSupplier != null) {
                        val image = iconSupplier.get().use { NativeImage.read(it) }
                        val id = registerPackTexture(packId, image)
                        packIconCache[packId] = id
                        return id
                    }
                }
            } catch (_: Exception) {
            }
        }

        return Identifier.ofVanilla("textures/misc/unknown_pack.png")
    }

    private fun registerPackTexture(packId: String, image: NativeImage): Identifier {
        val client = client ?: return Identifier.ofVanilla("textures/misc/unknown_pack.png")
        val sanitized = packId.lowercase().replace(Regex("[^a-z0-9_.-/]"), "_")
        val id = Identifier.of("resourcepackprofiles", "edit_pack_icon/$sanitized")

        if (id in registeredPackTextures) {
            client.textureManager.destroyTexture(id)
        }

        val texture = NativeImageBackedTexture({ "resourcepackprofiles/edit_pack_icon/$sanitized" }, image)
        client.textureManager.registerTexture(id, texture)
        registeredPackTextures.add(id)
        return id
    }

    private fun onSave() {
        val newName = nameField.text.trim()
        if (newName.isEmpty()) return

        if (newName != originalName) {
            if (!ProfileManager.renameProfile(originalName, newName)) return
            ProfileManager.updateProfile(newName, selectedPacks)
        } else {
            ProfileManager.updateProfile(originalName, selectedPacks)
        }

        close()
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        val centerX = width / 2
        val columnWidth = centerX - 12
        val leftX = 4
        val rightX = centerX + 8
        val listHeight = listBottom - listTop

        // Column headers
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Available"), leftX + columnWidth / 2, listTop - 10, 0xAAAAAA or (0xFF shl 24))
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Selected"), rightX + columnWidth / 2, listTop - 10, 0xAAAAAA or (0xFF shl 24))

        // Draw list backgrounds
        context.fill(leftX, listTop, leftX + columnWidth, listBottom, 0x80000000.toInt())
        context.fill(rightX, listTop, rightX + columnWidth, listBottom, 0x80000000.toInt())

        // Clamp scroll values
        val maxAvailScroll = ((availablePacks.size * entryHeight) - listHeight).coerceAtLeast(0)
        val maxSelScroll = ((selectedPacks.size * entryHeight) - listHeight).coerceAtLeast(0)
        availableScrollY = availableScrollY.coerceIn(0.0, maxAvailScroll.toDouble())
        selectedScrollY = selectedScrollY.coerceIn(0.0, maxSelScroll.toDouble())

        // Enable scissor for left list
        context.enableScissor(leftX, listTop, leftX + columnWidth, listBottom)
        renderPackList(context, availablePacks, leftX, columnWidth, availableScrollY.toInt(), mouseX, mouseY, false)
        context.disableScissor()

        // Enable scissor for right list
        context.enableScissor(rightX, listTop, rightX + columnWidth, listBottom)
        renderPackList(context, selectedPacks, rightX, columnWidth, selectedScrollY.toInt(), mouseX, mouseY, true)
        context.disableScissor()

        // Draw scrollbar indicators
        if (availablePacks.size * entryHeight > listHeight) {
            drawScrollbar(context, leftX + columnWidth - 4, listTop, listHeight, availableScrollY, maxAvailScroll)
        }
        if (selectedPacks.size * entryHeight > listHeight) {
            drawScrollbar(context, rightX + columnWidth - 4, listTop, listHeight, selectedScrollY, maxSelScroll)
        }
    }

    private fun renderPackList(
        context: DrawContext,
        packs: List<String>,
        x: Int,
        columnWidth: Int,
        scrollOffset: Int,
        mouseX: Int,
        mouseY: Int,
        isSelectedList: Boolean
    ) {
        for ((index, packId) in packs.withIndex()) {
            val entryY = listTop + index * entryHeight - scrollOffset
            if (entryY + entryHeight < listTop || entryY > listBottom) continue

            val iconX = x + listPadding
            val iconY = entryY + (entryHeight - packIconSize) / 2

            val isHovered = mouseX >= x && mouseX < x + columnWidth
                    && mouseY >= entryY.coerceAtLeast(listTop) && mouseY < (entryY + entryHeight).coerceAtMost(listBottom)
                    && mouseY >= listTop && mouseY < listBottom

            // Pack icon
            val iconId = getPackIconId(packId)
            context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, iconX, iconY, 0f, 0f, packIconSize, packIconSize, packIconSize, packIconSize)

            // On hover: white haze over icon + arrow sprites on top of icon
            if (isHovered) {
                context.fill(iconX, iconY, iconX + packIconSize, iconY + packIconSize, 0x80FFFFFF.toInt())

                if (isSelectedList) {
                    val arrowRegion = getHoveredArrowRegion(mouseX, mouseY, iconX, iconY)

                    val unselectSprite = if (arrowRegion == ArrowRegion.UNSELECT) UNSELECT_HIGHLIGHTED else UNSELECT
                    context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, unselectSprite, iconX, iconY, packIconSize, packIconSize)

                    if (index > 0) {
                        val upSprite = if (arrowRegion == ArrowRegion.MOVE_UP) MOVE_UP_HIGHLIGHTED else MOVE_UP
                        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, upSprite, iconX, iconY, packIconSize, packIconSize)
                    }

                    if (index < packs.size - 1) {
                        val downSprite = if (arrowRegion == ArrowRegion.MOVE_DOWN) MOVE_DOWN_HIGHLIGHTED else MOVE_DOWN
                        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, downSprite, iconX, iconY, packIconSize, packIconSize)
                    }
                } else {
                    val selectSprite = if (mouseX >= iconX && mouseX < iconX + packIconSize && mouseY >= iconY && mouseY < iconY + packIconSize) SELECT_HIGHLIGHTED else SELECT
                    context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, selectSprite, iconX, iconY, packIconSize, packIconSize)
                }
            }

            // Pack name and description
            val packProfile = resolvePackProfile(packId)
            val displayName = packProfile?.displayName?.string ?: packId
            val textX = x + listPadding + packIconSize + 4
            val maxTextWidth = columnWidth - packIconSize - listPadding * 2 - 4
            context.drawText(textRenderer, Text.literal(truncateText(displayName, maxTextWidth)), textX, entryY + 4, 0xFFFFFF or (0xFF shl 24), true)

            val description = packProfile?.description?.string ?: ""
            if (description.isNotEmpty()) {
                context.drawText(textRenderer, Text.literal(truncateText(description, maxTextWidth)), textX, entryY + 16, 0x808080 or (0xFF shl 24), false)
            }
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

    private fun drawScrollbar(context: DrawContext, x: Int, top: Int, height: Int, scrollPos: Double, maxScroll: Int) {
        if (maxScroll <= 0) return
        val barHeight = (height.toDouble() * height / (height + maxScroll)).toInt().coerceAtLeast(8)
        val barY = top + ((height - barHeight) * (scrollPos / maxScroll)).toInt()
        context.fill(x, barY, x + 3, barY + barHeight, 0x80FFFFFF.toInt())
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        val mx = click.x().toInt()
        val my = click.y().toInt()

        if (my >= listTop && my < listBottom) {
            val centerX = width / 2
            val columnWidth = centerX - 12
            val leftX = 4
            val rightX = centerX + 8

            // Click on available pack (left) → add to selected
            if (mx >= leftX && mx < leftX + columnWidth) {
                val index = (my - listTop + availableScrollY.toInt()) / entryHeight
                if (index in availablePacks.indices) {
                    val entryY = listTop + index * entryHeight - availableScrollY.toInt()
                    val iconX = leftX + listPadding
                    val iconY = entryY + (entryHeight - packIconSize) / 2
                    // Only add if clicking on the icon area
                    if (mx >= iconX && mx < iconX + packIconSize && my >= iconY && my < iconY + packIconSize) {
                        selectedPacks.add(availablePacks[index])
                        recomputeAvailable()
                        return true
                    }
                }
            }

            // Click on selected pack (right) — check arrow regions on icon
            if (mx >= rightX && mx < rightX + columnWidth) {
                val index = (my - listTop + selectedScrollY.toInt()) / entryHeight
                if (index in selectedPacks.indices) {
                    val entryY = listTop + index * entryHeight - selectedScrollY.toInt()
                    val iconX = rightX + listPadding
                    val iconY = entryY + (entryHeight - packIconSize) / 2
                    val region = getHoveredArrowRegion(mx, my, iconX, iconY)

                    when (region) {
                        ArrowRegion.UNSELECT -> {
                            selectedPacks.removeAt(index)
                            recomputeAvailable()
                        }
                        ArrowRegion.MOVE_UP -> {
                            if (index > 0) {
                                val tmp = selectedPacks[index]
                                selectedPacks[index] = selectedPacks[index - 1]
                                selectedPacks[index - 1] = tmp
                            }
                        }
                        ArrowRegion.MOVE_DOWN -> {
                            if (index < selectedPacks.size - 1) {
                                val tmp = selectedPacks[index]
                                selectedPacks[index] = selectedPacks[index + 1]
                                selectedPacks[index + 1] = tmp
                            }
                        }
                        ArrowRegion.NONE -> {}
                    }
                    return true
                }
            }
        }

        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (mouseY >= listTop && mouseY < listBottom) {
            val centerX = width / 2

            if (mouseX < centerX) {
                availableScrollY = (availableScrollY - verticalAmount * scrollSpeed)
                    .coerceAtLeast(0.0)
                return true
            } else {
                selectedScrollY = (selectedScrollY - verticalAmount * scrollSpeed)
                    .coerceAtLeast(0.0)
                return true
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun close() {
        // Clean up registered pack textures
        val client = client
        if (client != null) {
            for (id in registeredPackTextures) {
                client.textureManager.destroyTexture(id)
            }
        }
        registeredPackTextures.clear()
        packIconCache.clear()

        client?.setScreen(parent)
    }
}
