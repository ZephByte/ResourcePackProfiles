package org.zephbyte.resourcepackprofiles.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.zephbyte.resourcepackprofiles.client.profile.BackupChoice
import org.zephbyte.resourcepackprofiles.client.profile.ProfileManager
import org.zephbyte.resourcepackprofiles.client.ui.FlatButton
import org.zephbyte.resourcepackprofiles.client.ui.FlatCheckbox
import org.zephbyte.resourcepackprofiles.client.ui.Theme
import org.zephbyte.resourcepackprofiles.client.util.ScreenUtils
import org.zephbyte.resourcepackprofiles.client.util.showScreen

/**
 * The mod's settings. Every change is saved as it is made, so there is no Save button.
 *
 * @param openVanilla switches to the vanilla resource pack screen; null when that screen is not
 *   reachable from here (the profile screen was opened some other way, e.g. from Mod Menu).
 */
class SettingsScreen(
    private val parent: Screen,
    private val openVanilla: (() -> Unit)?
) : Screen(Component.translatable("screen.resourcepackprofiles.settings.title")) {

    private val backupButtons = mutableMapOf<BackupChoice, FlatButton>()

    private val panelWidth get() = (width - 40).coerceAtMost(320)
    private val panelHeight get() = if (openVanilla != null) 226 else 204
    private val panelX get() = (width - panelWidth) / 2
    private val panelY get() = ((height - panelHeight) / 2).coerceAtLeast(4)
    private val left get() = panelX + 12
    private val inner get() = panelWidth - 24

    // Vertical positions of each block, relative to the panel.
    private val replaceY = 32
    private val autoCheckY = 72
    private val backupY = 114
    private val vanillaY = 172

    override fun init() {
        addRenderableWidget(
            FlatCheckbox(
                left, panelY + replaceY, font,
                Component.translatable("setting.resourcepackprofiles.replace_vanilla"),
                ProfileManager.replacesVanillaScreen()
            ) { ProfileManager.setReplacesVanillaScreen(it) }
        )

        addRenderableWidget(
            FlatCheckbox(
                left, panelY + autoCheckY, font,
                Component.translatable("setting.resourcepackprofiles.auto_check"),
                ProfileManager.autoCheckSetting() == true
            ) { ProfileManager.setAutoCheckUpdates(it) }
        )

        // One button per choice; the current one is drawn filled.
        backupButtons.clear()
        val gap = 4
        val choices = BackupChoice.entries
        val buttonWidth = (inner - gap * (choices.size - 1)) / choices.size
        choices.forEachIndexed { i, choice ->
            val button = FlatButton(
                left + i * (buttonWidth + gap), panelY + backupY + 12, buttonWidth, 16,
                Component.translatable("setting.resourcepackprofiles.backup." + choice.id)
            ) {
                ProfileManager.setBackupOnUpdateAll(choice)
                syncBackupButtons()
            }
            backupButtons[choice] = button
            addRenderableWidget(button)
        }
        syncBackupButtons()

        if (openVanilla != null) {
            addRenderableWidget(
                FlatButton(left, panelY + vanillaY, inner, 16, Component.translatable("menu.resourcepackprofiles.open_vanilla")) {
                    openVanilla.invoke()
                }
            )
        }

        addRenderableWidget(FlatButton(left, panelY + panelHeight - 28, inner, 16, CommonComponents.GUI_DONE) { onClose() })
    }

    private fun syncBackupButtons() {
        val current = ProfileManager.backupOnUpdateAll()
        backupButtons.forEach { (choice, button) ->
            button.style = if (choice == current) FlatButton.Style.PRIMARY else FlatButton.Style.GHOST
        }
    }

    override fun extractBackground(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractBackground(context, mouseX, mouseY, delta)
        Theme.raised(context, panelX, panelY, panelWidth, panelHeight)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(context, mouseX, mouseY, delta)
        context.text(font, title, left, panelY + 12, Theme.TEXT, true)

        hint(context, "setting.resourcepackprofiles.replace_vanilla.hint", panelY + replaceY + 15)
        hint(context, "setting.resourcepackprofiles.replace_vanilla.hint2", panelY + replaceY + 25)

        hint(context, "setting.resourcepackprofiles.auto_check.hint", panelY + autoCheckY + 15)
        hint(context, "setting.resourcepackprofiles.auto_check.hint2", panelY + autoCheckY + 25)

        val backupLabel = Component.translatable("setting.resourcepackprofiles.backup")
        context.text(font, backupLabel, left, panelY + backupY, Theme.TEXT_SECONDARY, true)
        // The hint describes whichever choice is active.
        hint(context, "setting.resourcepackprofiles.backup." + ProfileManager.backupOnUpdateAll().id + ".hint", panelY + backupY + 33)
    }

    private fun hint(context: GuiGraphicsExtractor, key: String, y: Int) {
        val text = Component.translatable(key).string
        context.text(font, ScreenUtils.truncate(font, text, inner), left, y, Theme.TEXT_MUTED, true)
    }

    override fun onClose() {
        minecraft.showScreen(parent)
    }
}
