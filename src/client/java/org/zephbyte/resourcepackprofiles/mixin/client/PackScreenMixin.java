package org.zephbyte.resourcepackprofiles.mixin.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.zephbyte.resourcepackprofiles.client.screen.ProfileScreen;

@Mixin(PackSelectionScreen.class)
public abstract class PackScreenMixin extends Screen {

    @Shadow private Button doneButton;

    protected PackScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addProfilesButton(CallbackInfo ci) {
        this.addRenderableWidget(Button.builder(Component.literal("☰"), btn ->
                this.minecraft.setScreen(new ProfileScreen((PackSelectionScreen) (Object) this)))
                .bounds(this.doneButton.getX() + this.doneButton.getWidth() + 4, this.doneButton.getY(), 20, 20)
                .tooltip(Tooltip.create(Component.literal("Profiles")))
                .build());
    }
}
