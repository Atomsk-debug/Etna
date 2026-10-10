package it.cosimo.etna.mixin;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import it.cosimo.etna.EtnaProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Splits the Vulkan backend's submit and counts the passes, copies and clears a frame records, for the
 * performance log and the benchmark. submit() first hands the frame to the driver (on MoltenVK the frame
 * becomes Metal commands there, on this thread) and then waits until the frame from two submits ago is done
 * on the GPU: encode is the part before the first wait, wait is that wait. Each hook is one static check
 * while the log is off, and each is optional (require = 0): if a later game version renames what it hooks,
 * the log only loses that figure.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
	@Inject(method = "submit()V", at = @At("HEAD"), require = 0)
	private void etna$encodeBegin(CallbackInfo ci) {
		EtnaProfiler.encodeBegin();
	}

	@Inject(
		method = "submit()V",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z",
			ordinal = 0
		),
		require = 0
	)
	private void etna$waitBegin(CallbackInfo ci) {
		EtnaProfiler.waitBegin();
	}

	@Inject(
		method = "submit()V",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z",
			ordinal = 0,
			shift = At.Shift.AFTER
		),
		require = 0
	)
	private void etna$waitEnd(CallbackInfo ci) {
		EtnaProfiler.waitEnd();
	}

	@Inject(method = "createRenderPass", at = @At("HEAD"), require = 0)
	private void etna$countRenderPass(CallbackInfoReturnable<RenderPassBackend> cir) {
		EtnaProfiler.countRenderPass();
	}

	@Inject(
		method = {"writeToBuffer", "copyToBuffer", "writeToTexture", "copyBufferToTexture", "copyTextureToBuffer", "copyTextureToTexture"},
		at = @At("HEAD"),
		require = 0
	)
	private void etna$countCopy(CallbackInfo ci) {
		EtnaProfiler.countCopy();
	}

	@Inject(method = {"clearColorTexture", "clearColorAndDepthTextures", "clearDepthTexture"}, at = @At("HEAD"), require = 0)
	private void etna$countClear(CallbackInfo ci) {
		EtnaProfiler.countClear();
	}
}
