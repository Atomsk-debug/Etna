package it.cosimo.etna.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import it.cosimo.etna.EtnaWeather;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws rain and snow in Etna's light (EtnaWeather): the private render method every weather draw goes
 * through, with or without Improved Transparency, gets Etna's terrain block bound and draws through Etna's
 * copy of its pipeline. Both happen before it sets the pipeline and its own uniforms, which it leaves as
 * they are. Etna off, both leave everything vanilla's.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
	private static final String RENDER = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
		+ "Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V";

	@Inject(method = RENDER, at = @At("HEAD"))
	private void etna$bindLight(WeatherRenderState renderState, RenderPass renderPass, RenderPipeline renderPipeline, CallbackInfo ci) {
		EtnaWeather.bind(renderPass);
	}

	@ModifyVariable(method = RENDER, at = @At("HEAD"), argsOnly = true)
	private RenderPipeline etna$weatherPipeline(RenderPipeline renderPipeline) {
		return EtnaWeather.pipelineFor(renderPipeline);
	}
}
