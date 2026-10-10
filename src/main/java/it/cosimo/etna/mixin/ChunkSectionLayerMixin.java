package it.cosimo.etna.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import it.cosimo.etna.EtnaShadows;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Swaps a terrain layer's pipeline for its shadow-receiving copy while the shadow map is bound. */
@Mixin(ChunkSectionLayer.class)
public abstract class ChunkSectionLayerMixin {
	@Inject(method = "pipeline", at = @At("RETURN"), cancellable = true)
	private void etna$shadowReceiver(boolean multiDraw, boolean cloudsInFog, CallbackInfoReturnable<RenderPipeline> cir) {
		RenderPipeline receiver = EtnaShadows.receiverFor(cir.getReturnValue());
		if (receiver != cir.getReturnValue()) {
			cir.setReturnValue(receiver);
		}
	}
}
