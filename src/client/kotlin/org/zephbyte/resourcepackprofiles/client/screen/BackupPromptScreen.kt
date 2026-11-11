package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.zephbyte.resourcepackprofiles.client.profile.BackupChoice
import org.zephbyte.resourcepackprofiles.client.profile.ModSettings
import org.zephbyte.resourcepackprofiles.client.ui.FlatButton
import org.zephbyte.resourcepackprofiles.client.ui.FlatCheckbox
import org.zephbyte.resourcepackprofiles.client.ui.Theme
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import org.zephbyte.resourcepackprofiles.client.util.showScreen

/**
 * Asked before updating several packs at once: should a backup copy of the profile be saved first? With "Don't ask
 * again" ticked the answer is remembered, which stops this screen from appearing again
 * (changeable later under Settings).
 *
 * [onChoice] runs after returning to [parent], with true for "back up first". It is not called
 * when the user cancels.
 */
class BackupPromptScreen(
    private val parent: Screen,
    private val profileName: String,
    private val updateCount: Int,
    private val onChoice: (Boolean) -> Unit
) : Screen(Component.translatable("screen.resourcepackprofiles.backup.title")) {

    // Kept outside the widget so the tick survives init() rerunning on a window resize.
    private var remember = false

    private val panelWidth get() = (width - 40).coerceAtMost(300)
    private val panelHeight = 116
    private val panelX get() = (width - panelWidth) / 2
    private val panelY get() = (height - panelHeight) / 2

    override fun init() {
        val inner = panelWidth - 24
        val left = panelX + 12

        val gap = 4
        val buttonWidth = (inner - gap) / 2
        addRenderableWidget(
            FlatButton(
                left, panelY + 52, buttonWidth, 16,
                Component.translatable("button.resourcepackprofiles.backup_and_update"),
                style = FlatButton.Style.PRIMARY
            ) { choose(true) }
        )
        addRenderableWidget(
            FlatButton(
                left + buttonWidth + gap, panelY + 52, inner - buttonWidth - gap, 16,
                Component.translatable("button.resourcepackprofiles.update_only")
            ) { choose(false) }
        )
        addRenderableWidget(
            FlatButton(left, panelY + 72, inner, 16, CommonComponents.GUI_CANCEL, style = FlatButton.Style.DANGER) { onClose() }
        )

        // Last, and centred: it modifies the answer rather than being one.
        val label = Component.translatable("button.resourcepackprofiles.dont_ask_again")
        val checkboxWidth = FlatCheckbox.widthFor(font, label)
        addRenderableWidget(
            FlatCheckbox(panelX + (panelWidth - checkboxWidth) / 2, panelY + 95, font, label, remember) { remember = it }
        )
    }

    private fun choose(backup: Boolean) {
        if (remember) ModSettings.backupChoice = if (backup) BackupChoice.ALWAYS else BackupChoice.NEVER
        minecraft.showScreen(parent)
        onChoice(backup)
    }

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
        Theme.raised(context, panelX, panelY, panelWidth, panelHeight)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
        val left = panelX + 12
        val inner = panelWidth - 24
        context.text(font, title, left, panelY + 12, Theme.TEXT, true)
        val line1 = Component.translatable("screen.resourcepackprofiles.backup.message", updateCount, profileName).string
        val line2 = Component.translatable("screen.resourcepackprofiles.backup.detail").string
        context.text(font, ScreenUtils.truncate(font, line1, inner), left, panelY + 26, Theme.TEXT_SECONDARY, true)
        context.text(font, ScreenUtils.truncate(font, line2, inner), left, panelY + 36, Theme.TEXT_MUTED, true)
    }

    override fun onClose() {
        minecraft.showScreen(parent)
    }
}
