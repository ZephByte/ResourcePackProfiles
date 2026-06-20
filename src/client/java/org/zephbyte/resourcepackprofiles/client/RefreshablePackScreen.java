package org.zephbyte.resourcepackprofiles.client;

import net.minecraft.client.gui.screens.Screen;

/**
 * Implemented (via mixin) by the vanilla {@code PackSelectionScreen}.
 *
 * <p>The pack selection screen takes a one-time snapshot of the enabled packs into its
 * internal model when it is constructed, and writes that snapshot back to the game on close.
 * When a profile is applied from the profiles screen, the live packs change behind the
 * original screen's back, so returning to that stale instance and closing it would overwrite
 * the freshly applied packs. This lets the profiles screen swap in a fresh pack selection
 * screen whose model reflects the current repository state instead.
 */
public interface RefreshablePackScreen {
    Screen rpp_createRefreshedScreen();
}
