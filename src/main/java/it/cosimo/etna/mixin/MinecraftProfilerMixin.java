package it.cosimo.etna.mixin;

import it.cosimo.etna.EtnaProfiler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times what the game loop does around a frame, for the performance log and the benchmark: the game's
 * ticks (run before a frame when one is due, so a frame that ran one takes longer), and getting the next
 * window image before the frame and showing the finished one after it, which wait when no image is free
 * (vsync, or the GPU behind). Each hook is one static check while the log is off, and each is optional
 * (require = 0): if a later game version renames what it hooks, the log only loses that figure.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftProfilerMixin {
	@Inject(method = "tick()V", at = @At("HEAD"), require = 0)
	private void etna$tickBegin(CallbackInfo ci) {
		EtnaProfiler.tickBegin();
	}

	@Inject(method = "tick()V", at = @At("RETURN"), require = 0)
	private void etna$tickEnd(CallbackInfo ci) {
		EtnaProfiler.tickEnd();
	}

	@Inject(
		method = "renderFrame(Z)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"),
		require = 0
	)
	private void etna$acquireBegin(CallbackInfo ci) {
		EtnaProfiler.surfaceBegin();
	}

	@Inject(
		method = "renderFrame(Z)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V", shift = At.Shift.AFTER),
		require = 0
	)
	private void etna$acquireEnd(CallbackInfo ci) {
		EtnaProfiler.surfaceEnd();
	}

	@Inject(
		method = "renderFrame(Z)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"),
		require = 0
	)
	private void etna$presentBegin(CallbackInfo ci) {
		EtnaProfiler.surfaceBegin();
	}

	@Inject(
		method = "renderFrame(Z)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER),
		require = 0
	)
	private void etna$presentEnd(CallbackInfo ci) {
		EtnaProfiler.surfaceEnd();
	}
}
