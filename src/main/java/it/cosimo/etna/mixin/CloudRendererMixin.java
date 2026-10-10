package it.cosimo.etna.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import it.cosimo.etna.EtnaClouds;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lights vanilla's clouds by the sun (EtnaClouds): the private render method every cloud draw goes
 * through, with or without Improved Transparency, gets Etna's terrain block bound and draws through the
 * lit copy of its pipeline. Both happen before it sets the pipeline and its own uniforms, which it leaves
 * as they are.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	private static final String RENDER = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V";

	@Inject(method = RENDER, at = @At("HEAD"))
	private void etna$bindLight(RenderPass renderPass, RenderPipeline renderPipeline, CallbackInfo ci) {
		EtnaClouds.bind(renderPass);
	}

	@ModifyVariable(method = RENDER, at = @At("HEAD"), argsOnly = true)
	private RenderPipeline etna$litPipeline(RenderPipeline renderPipeline) {
		return EtnaClouds.pipelineFor(renderPipeline);
	}
}
