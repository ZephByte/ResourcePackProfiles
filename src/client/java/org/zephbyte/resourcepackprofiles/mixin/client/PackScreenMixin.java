package org.zephbyte.resourcepackprofiles.mixin.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.zephbyte.resourcepackprofiles.client.screen.ProfileScreen;

@Mixin(PackScreen.class)
public abstract class PackScreenMixin extends Screen {

    @Shadow private ButtonWidget doneButton;

    protected PackScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addProfilesButton(CallbackInfo ci) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal("\u2630"), btn ->
                this.client.setScreen(new ProfileScreen((PackScreen) (Object) this)))
                .dimensions(this.doneButton.getX() + this.doneButton.getWidth() + 4, this.doneButton.getY(), 20, 20)
                .tooltip(Tooltip.of(Text.literal("Profiles")))
                .build());
    }
}
