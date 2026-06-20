package org.zephbyte.resourcepackprofiles.mixin.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.zephbyte.resourcepackprofiles.client.ProfileButton;
import org.zephbyte.resourcepackprofiles.client.RefreshablePackScreen;
import org.zephbyte.resourcepackprofiles.client.screen.ProfileScreen;

import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(PackSelectionScreen.class)
public abstract class PackScreenMixin extends Screen implements RefreshablePackScreen {

    @Shadow private Button doneButton;
    @Shadow @Final private PackSelectionModel model;
    @Shadow @Final private Path packDir;

    @Unique private ProfileButton rpp_profilesButton;

    protected PackScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addProfilesButton(CallbackInfo ci) {
        ProfileButton button = new ProfileButton(rpp_profilesButtonX(), this.doneButton.getY(), 20, 20,
                Component.literal("Profiles"),
                btn -> this.minecraft.setScreen(new ProfileScreen((PackSelectionScreen) (Object) this)));
        button.setTooltip(Tooltip.create(Component.literal("Profiles")));
        this.rpp_profilesButton = button;
        this.addRenderableWidget(button);
    }

    /**
     * On resize, PackSelectionScreen re-arranges its layout via repositionElements() instead of
     * re-running init(), and our button isn't part of that layout — so re-anchor it to the
     * (now-repositioned) Done button here, otherwise it keeps stale coordinates and detaches.
     */
    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void repositionProfilesButton(CallbackInfo ci) {
        if (this.rpp_profilesButton != null) {
            this.rpp_profilesButton.setX(rpp_profilesButtonX());
            this.rpp_profilesButton.setY(this.doneButton.getY());
        }
    }

    /**
     * Places the button just left of the "Open Pack Folder" button (the left of the two centered
     * 150px-wide bottom buttons). Done sits at width/2 + 4, so the Open Pack Folder button's left
     * edge is doneButton.getX() - 158. We avoid the space right of Done because the popular Entity
     * Texture Features mod puts its button there.
     */
    @Unique
    private int rpp_profilesButtonX() {
        return this.doneButton.getX() - 158 - 8 - 20; // 158 = gap to Open Pack Folder's left edge, 8px pad, 20px button
    }

    /**
     * Builds a fresh pack selection screen that snapshots the current (post-profile) packs,
     * reusing this screen's existing output consumer so closing it still applies the packs and
     * navigates back to the same parent. Returning here instead of to {@code this} prevents the
     * stale snapshot from overwriting packs a profile just applied.
     */
    @Override
    public Screen rpp_createRefreshedScreen() {
        Consumer<PackRepository> output = ((PackSelectionModelAccessor) (Object) this.model).getOutput();
        return new PackSelectionScreen(
                this.minecraft.getResourcePackRepository(),
                output,
                this.packDir,
                this.getTitle());
    }
}
