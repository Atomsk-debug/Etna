package it.cosimo.etna.mixin;

import it.cosimo.etna.EtnaGpuTimer;
import it.cosimo.etna.EtnaProfiler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times each frame for the performance log and the benchmark. Minecraft.renderFrame calls extract, then
 * render (which records the whole frame, the level included), then submits: the start of render is
 * where one frame interval ends and the next begins, and where the benchmark moves on. Each hook is one
 * static check while the log is off.
 *
 * The GPU timer's marks after the hand and after the post effects are here too, at points with no render
 * pass open. They are optional (require = 0): if a later game version renames the methods around them, the
 * GPU figures stop and the game runs on.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererProfilerMixin {
	@Inject(method = "render()V", at = @At("HEAD"))
	private void etna$profileFrameBegin(CallbackInfo ci) {
		EtnaProfiler.frameBegin();
	}

	@Inject(method = "render()V", at = @At("RETURN"))
	private void etna$profileFrameEnd(CallbackInfo ci) {
		EtnaProfiler.frameEnd();
	}

	@Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	private void etna$profileExtractBegin(CallbackInfo ci) {
		EtnaProfiler.extractBegin();
	}

	@Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
	private void etna$profileExtractEnd(CallbackInfo ci) {
		EtnaProfiler.extractEnd();
	}

	/** Only frames that draw the world read Etna's shadow figures and the camera. */
	@Inject(method = "renderLevel()V", at = @At("HEAD"))
	private void etna$profileLevel(CallbackInfo ci) {
		EtnaProfiler.levelBegin();
	}

	/** The hand (and the screen effects drawn with it) is done; the post effects come next. */
	@Inject(
		method = "renderLevel()V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
			shift = At.Shift.AFTER
		),
		require = 0
	)
	private void etna$gpuHandEnd(CallbackInfo ci) {
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.HAND_END);
	}

	/** The post effects are done (Etna's final chain, then vanilla's); the HUD comes next. */
	@Inject(
		method = "render()V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER),
		require = 0
	)
	private void etna$gpuFinalEnd(CallbackInfo ci) {
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.FINAL_END);
	}
}
