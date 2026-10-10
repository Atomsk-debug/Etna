package it.cosimo.etna.mixin;

import java.nio.IntBuffer;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import it.cosimo.etna.EtnaProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts the draws a frame asks the Vulkan backend for, as Metal will run them: a direct draw is one, a
 * multi-draw or an indirect draw is as many as it holds (MoltenVK turns an indirect draw of n commands into
 * n Metal draws). Terrain goes through the indirect draws when vanilla uses them, so their share shows how
 * much of the frame they carry. Each hook is one static check while the log is off, and each is optional
 * (require = 0): if a later game version renames a method, the log only loses that count.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {
	@Inject(method = {"drawIndexed(IIIII)V", "draw(IIII)V"}, at = @At("HEAD"), require = 0)
	private void etna$countDraw(CallbackInfo ci) {
		EtnaProfiler.countDraws(1);
	}

	@Inject(method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"), require = 0)
	private void etna$countMultiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount, CallbackInfo ci) {
		EtnaProfiler.countDraws(drawCount);
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"), require = 0)
	private void etna$countMultiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount, CallbackInfo ci) {
		EtnaProfiler.countDraws(drawCount);
	}

	@Inject(method = "drawIndexedIndirect(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V", at = @At("HEAD"), require = 0)
	private void etna$countDrawIndexedIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		EtnaProfiler.countIndirectDraws(drawCount);
	}

	@Inject(method = "drawIndirect(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V", at = @At("HEAD"), require = 0)
	private void etna$countDrawIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		EtnaProfiler.countIndirectDraws(drawCount);
	}
}
