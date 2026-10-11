package it.cosimo.etna.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import it.cosimo.etna.EtnaEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lights entities, block entities, armour, items and the hand like the terrain (EtnaEntities): the private method
 * every one of their draws goes through draws with Etna's copy of its pipeline while Etna lights it, and binds
 * Etna's terrain block, shadow maps and cloud texture on the pass together with the default uniforms, after the
 * pipeline is set. Both are optional (require = 0): if the game's code changes under them, entities keep vanilla's
 * light and the rest of Etna carries on. The class is named rather than referenced, as Etna needs none of its API.
 */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.PreparedRenderType")
public abstract class PreparedRenderTypeMixin {
	@ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true, require = 0)
	private RenderPipeline etna$litPipeline(RenderPipeline renderPipeline) {
		return EtnaEntities.pipelineFor(renderPipeline);
	}

	@ModifyArg(
		method = "draw",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;bindDefaultUniforms(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
		),
		require = 0
	)
	private RenderPass etna$bindLight(RenderPass renderPass) {
		EtnaEntities.bind(renderPass);
		return renderPass;
	}
}
