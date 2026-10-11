package it.cosimo.etna.mixin;

import java.util.List;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import it.cosimo.etna.EtnaClient;
import it.cosimo.etna.EtnaFrame;
import it.cosimo.etna.EtnaGpuTimer;
import it.cosimo.etna.EtnaScale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks Etna's two post chains into the frame.
 *
 * Since 26.4 GameRenderer.update rebuilds a list of requested post effects every frame (player
 * effects, spectated entity), and the renderer builds and runs every chain in that list after the
 * level and the hand. Etna puts its final chain (bloom, colour grade) first, so vanilla effects still
 * run on top of it, and a chain that fails to load lands in vanilla's failed list instead of being
 * retried every frame. The world chain (ambient occlusion, anti-aliasing) runs earlier, between the
 * level and the hand, and the level's projection gets this frame's anti-aliasing jitter. Glowing-entity
 * outlines are blended in by the world pass, so vanilla's later blend is skipped on those frames.
 *
 * With a render scale below 1 (EtnaScale), mainRenderTarget() hands out Etna's smaller target from the start of
 * the level until the world chain has written the full-size picture, so the level is drawn smaller; the hand and
 * everything after it use the full-size target, which this class reads through the field.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private List<Identifier> requestedPostEffects;

	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Shadow
	@Final
	private CrossFrameResourcePool resourcePool;

	@Inject(method = "update", at = @At("RETURN"))
	private void etna$requestPostEffect(DeltaTracker deltaTracker, CallbackInfo ci) {
		Identifier effect = EtnaClient.activeEffect();
		if (effect != null && !requestedPostEffects.contains(effect)) {
			requestedPostEffects.add(0, effect);
		}
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void etna$frameStart(CallbackInfo ci) {
		EtnaScale.beginFrame(Minecraft.getInstance().level != null);
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void etna$levelSize(CallbackInfo ci) {
		EtnaScale.beginLevel(mainRenderTarget);
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void etna$levelEnd(CallbackInfo ci) {
		// Also here in case the hand's hook below never ran: nothing after the level may draw into the smaller target.
		EtnaScale.endLevel();
	}

	@Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
	private void etna$levelTarget(CallbackInfoReturnable<RenderTarget> cir) {
		RenderTarget level = EtnaScale.levelTarget(mainRenderTarget);
		if (level != mainRenderTarget) {
			cir.setReturnValue(level);
		}
	}

	@ModifyArg(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
		)
	)
	private Matrix4f etna$jitterProjection(Matrix4f projection) {
		// The jitter moves in the pixels the level is drawn at, which are the smaller target's with a render scale.
		return EtnaFrame.beginLevel(projection, gameRenderState.levelRenderState.cameraRenderState, EtnaScale.levelWidth(), EtnaScale.levelHeight());
	}

	@Inject(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"
		)
	)
	private void etna$worldEffects(CallbackInfo ci) {
		// The level is drawn: the GPU timer brackets the world pass (only while the log or the benchmark runs).
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.LEVEL_END);
		try {
			EtnaFrame.processWorld(mainRenderTarget, resourcePool);
		} finally {
			// The hand and everything after it draw at full size.
			EtnaScale.endLevel();
		}
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.WORLD_END);
	}

	@Redirect(
		method = "render",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;blitEntityOutline()V")
	)
	private void etna$outlineOnce(LevelRenderer levelRenderer) {
		if (!EtnaFrame.outlineDone()) {
			levelRenderer.blitEntityOutline();
		}
	}

	@Inject(method = "onResourceManagerReload", at = @At("TAIL"))
	private void etna$reloaded(ResourceManager resourceManager, CallbackInfo ci) {
		EtnaFrame.resourcesReloaded();
	}
}
