package org.zephbyte.resourcepackprofiles.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Icon button for the resource pack screen that opens the profiles UI. Instead of a text label
 * it renders a "stacked sheets" glyph (drawn with fills, mirroring ProfileScreen's import icon)
 * to represent saved pack load-order profiles.
 */
public class ProfileButton extends Button {

    public ProfileButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Standard button background (handles hover/disabled states), then the icon on top.
        // We intentionally skip the default label so only the glyph shows.
        this.extractDefaultSprite(context);
        drawProfilesIcon(context, this.getX(), this.getY());
    }

    /**
     * Draws three sheets stacked front-to-back to evoke saved pack load-orders. Each sheet is a
     * white face with a dark 1px border; drawing back-to-front lets nearer sheets occlude the
     * ones behind them, giving the stack depth. Coordinates assume a 20x20 button.
     */
    private static void drawProfilesIcon(GuiGraphicsExtractor context, int bx, int by) {
        card(context, bx + 3, by + 4);  // back
        card(context, bx + 5, by + 7);  // middle
        card(context, bx + 7, by + 10); // front
    }

    private static void card(GuiGraphicsExtractor context, int x, int y) {
        int w = 11;
        int h = 6;
        context.fill(x, y, x + w, y + h, 0xFF555555);                 // dark border base
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF); // white face
    }
}
