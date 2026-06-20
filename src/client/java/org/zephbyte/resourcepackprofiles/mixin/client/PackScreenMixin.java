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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.zephbyte.resourcepackprofiles.client.RefreshablePackScreen;
import org.zephbyte.resourcepackprofiles.client.screen.ProfileScreen;

import java.nio.file.Path;
import java.util.function.Consumer;

@Mixin(PackSelectionScreen.class)
public abstract class PackScreenMixin extends Screen implements RefreshablePackScreen {

    @Shadow private Button doneButton;
    @Shadow @Final private PackSelectionModel model;
    @Shadow @Final private Path packDir;

    protected PackScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addProfilesButton(CallbackInfo ci) {
        // Place the button just left of the "Open Pack Folder" button (the left of the two
        // centered 150px-wide bottom buttons). The Done button sits at width/2 + 4, so the
        // Open Pack Folder button's left edge is doneButton.getX() - 158. We avoid the space to
        // the right of Done because the popular Entity Texture Features mod puts its button there.
        int x = this.doneButton.getX() - 158 - 4 - 20;
        this.addRenderableWidget(Button.builder(Component.literal("☰"), btn ->
                this.minecraft.setScreen(new ProfileScreen((PackSelectionScreen) (Object) this)))
                .bounds(x, this.doneButton.getY(), 20, 20)
                .tooltip(Tooltip.create(Component.literal("Profiles")))
                .build());
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
