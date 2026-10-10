package it.cosimo.etna.mixin;

import net.minecraft.client.renderer.fog.FogData;
import org.spongepowered.asm.mixin.Mixin;

/** Setters for FogData's fog distances, in case they can't be written directly. Empty for now. */
@Mixin(FogData.class)
public interface FogDataAccessor {
}
