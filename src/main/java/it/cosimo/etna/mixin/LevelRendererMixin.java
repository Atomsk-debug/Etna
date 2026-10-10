package it.cosimo.etna.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import it.cosimo.etna.EtnaGpuTimer;
import it.cosimo.etna.EtnaLook;
import it.cosimo.etna.EtnaMotes;
import it.cosimo.etna.EtnaShadows;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders Etna's shadow map right before vanilla prepares the frame's chunk draws. The shadow pass
 * prepares its own draws, which write into the same per-frame chunk buffers; running after vanilla's
 * could grow those buffers and close the ones vanilla's draws still point at (a crash on respawn,
 * when many sections become visible at once). The live settings for the post effect are uploaded here
 * too, after the fireflies (EtnaMotes), whose count they carry: it is a point every frame passes with no render
 * pass open. Each injection tells the shadow pass which of vanilla's
 * two ways of preparing the draws is in use, so it prepares its own the same way. The GPU timer brackets
 * the shadow pass here too, as its timestamps must sit outside any render pass (only while the performance
 * log or the benchmark runs; otherwise each mark is one static check).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Inject(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
		)
	)
	private void etna$renderShadowMapIndirect(
		GraphicsResourceAllocator resourceAllocator,
		boolean renderOutline,
		CameraRenderState cameraState,
		GpuBufferSlice fogBuffer,
		boolean consistentDepthRequired,
		CallbackInfo ci
	) {
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.SHADOW_START);
		EtnaShadows.prepareFrame((LevelRenderer) (Object) this, levelRenderState, fogBuffer, true);
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.SHADOW_END);
		EtnaMotes.upload(levelRenderState);
		EtnaLook.upload();
	}

	@Inject(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
		)
	)
	private void etna$renderShadowMap(
		GraphicsResourceAllocator resourceAllocator,
		boolean renderOutline,
		CameraRenderState cameraState,
		GpuBufferSlice fogBuffer,
		boolean consistentDepthRequired,
		CallbackInfo ci
	) {
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.SHADOW_START);
		EtnaShadows.prepareFrame((LevelRenderer) (Object) this, levelRenderState, fogBuffer, false);
		EtnaGpuTimer.mark(EtnaGpuTimer.Mark.SHADOW_END);
		EtnaMotes.upload(levelRenderState);
		EtnaLook.upload();
	}
}
