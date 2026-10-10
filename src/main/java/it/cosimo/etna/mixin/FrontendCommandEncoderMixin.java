package it.cosimo.etna.mixin;

import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import it.cosimo.etna.EtnaProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures how long each frame spends submitting its work. The game submits once a frame, after
 * recording it. On Vulkan the backend then blocks until the frame from two submits ago has finished on
 * the GPU, so most of a long submit is the CPU waiting for the GPU (VulkanCommandEncoderMixin splits it).
 * This is the front end both backends share, so the figure exists on OpenGL too. The start of submit is
 * also where a timed frame gets its last GPU timestamp, after the HUD and the copy to the window.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class FrontendCommandEncoderMixin {
	@Inject(method = "submit()V", at = @At("HEAD"))
	private void etna$profileSubmitBegin(CallbackInfo ci) {
		EtnaProfiler.submitBegin();
	}

	@Inject(method = "submit()V", at = @At("RETURN"))
	private void etna$profileSubmitEnd(CallbackInfo ci) {
		EtnaProfiler.submitEnd();
	}
}
