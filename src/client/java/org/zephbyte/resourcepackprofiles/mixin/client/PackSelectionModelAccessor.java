package org.zephbyte.resourcepackprofiles.mixin.client;

import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Consumer;

/**
 * Exposes the output consumer the pack selection model commits to, so a refreshed
 * {@code PackSelectionScreen} can be rebuilt with the same "apply packs and return" behavior.
 */
@Mixin(PackSelectionModel.class)
public interface PackSelectionModelAccessor {
    @Accessor("output")
    Consumer<PackRepository> getOutput();
}
