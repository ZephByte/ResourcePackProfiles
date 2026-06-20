package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Shown after applying a profile whose pack list referenced packs that are no longer installed.
 * Lists the missing pack ids and returns to [parent] on dismiss.
 */
class MissingPacksScreen(
    private val parent: Screen?,
    private val missingIds: List<String>
) : Screen(Component.translatable("screen.resourcepackprofiles.missing_packs.title")) {

    override fun init() {
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { minecraft?.setScreen(parent) }
                .bounds(width / 2 - 50, height / 2 + 40, 100, 20)
                .build()
        )
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
        context.centeredText(font, title, width / 2, height / 2 - 40, 0xFFFFFFFF.toInt())
        for ((i, id) in missingIds.withIndex()) {
            context.centeredText(font, Component.literal("• $id"), width / 2, height / 2 - 20 + i * 12, 0xFFAAAAAA.toInt())
        }
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }
}
