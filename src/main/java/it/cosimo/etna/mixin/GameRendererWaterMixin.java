package it.cosimo.etna.mixin;

import it.cosimo.etna.EtnaSky;
import it.cosimo.etna.EtnaWater;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the frame tells EtnaWater about the camera and lets it, then EtnaSky, adjust vanilla's fog before
 * the upload.
 *
 * GameRenderer.extract works out the camera (extractCamera: what the camera is in, this frame's new
 * FogData) and then the rest of the level (the sky among it); renderLevel later uploads that FogData into
 * the Fog block every shader reads. EtnaWater reads the world at the end of extractCamera, while vanilla
 * reads it too, and edits the fog right before the upload, once the sky has been extracted as well.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererWaterMixin {
	@Inject(method = "extractCamera(Lnet/minecraft/client/DeltaTracker;F)V", at = @At("TAIL"))
	private void etna$extractWater(DeltaTracker deltaTracker, float worldPartialTicks, CallbackInfo ci) {
		EtnaWater.extract(((GameRenderer) (Object) this).gameRenderState().levelRenderState.cameraRenderState);
	}

	@Inject(
		method = "renderLevel()V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V"
		)
	)
	private void etna$waterFog(CallbackInfo ci) {
		LevelRenderState state = ((GameRenderer) (Object) this).gameRenderState().levelRenderState;
		EtnaWater.beforeFogUpload(state);
		// The sky after the water: in the open air it sets the fog's colour to the sky's horizon, which the
		// water leaves alone there, and it decides whether Etna's sky is drawn this frame.
		EtnaSky.beforeFogUpload(state);
	}
}
