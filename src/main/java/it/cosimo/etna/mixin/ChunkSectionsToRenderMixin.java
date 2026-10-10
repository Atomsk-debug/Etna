package it.cosimo.etna.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.cosimo.etna.EtnaShadows;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Binds the shadow map around vanilla terrain draws (not around Etna's own shadow pass or OIT), and while Etna is
 * on draws the override pipelines vanilla passes in (Improved Transparency's) with Etna's terrain shader.
 */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
	@ModifyVariable(method = "renderLayers", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private @Nullable RenderPipeline etna$override(@Nullable RenderPipeline renderPipelineOverride) {
		return EtnaShadows.overrideFor(renderPipelineOverride);
	}

	@ModifyVariable(method = "renderLayers", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private @Nullable RenderPipeline etna$overrideMultidraw(@Nullable RenderPipeline renderPipelineOverrideMultidraw) {
		return EtnaShadows.overrideFor(renderPipelineOverrideMultidraw);
	}

	@Inject(method = "renderLayers", at = @At("HEAD"))
	private void etna$bindShadowMap(
		ChunkSectionLayer[] layers,
		GpuSampler sampler,
		RenderPass renderPass,
		GpuTextureView atlas,
		GpuTextureView lightmap,
		ChunkSectionsToRender.@Nullable ImprovedFogTextures improvedFogTextures,
		@Nullable RenderPipeline renderPipelineOverride,
		@Nullable RenderPipeline renderPipelineOverrideMultidraw,
		CallbackInfo ci
	) {
		EtnaShadows.beginTerrain(renderPass, renderPipelineOverride, renderPipelineOverrideMultidraw);
	}

	@Inject(method = "renderLayers", at = @At("RETURN"))
	private void etna$endShadowedTerrain(
		ChunkSectionLayer[] layers,
		GpuSampler sampler,
		RenderPass renderPass,
		GpuTextureView atlas,
		GpuTextureView lightmap,
		ChunkSectionsToRender.@Nullable ImprovedFogTextures improvedFogTextures,
		@Nullable RenderPipeline renderPipelineOverride,
		@Nullable RenderPipeline renderPipelineOverrideMultidraw,
		CallbackInfo ci
	) {
		EtnaShadows.endTerrain();
	}
}
