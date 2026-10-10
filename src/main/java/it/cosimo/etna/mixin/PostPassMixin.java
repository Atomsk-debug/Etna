package it.cosimo.etna.mixin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import it.cosimo.etna.EtnaClient;
import it.cosimo.etna.EtnaLook;
import it.cosimo.etna.EtnaMotes;
import it.cosimo.etna.EtnaShadows;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla creates each post pass uniform block once, from the JSON, in a buffer nothing can write to
 * afterwards. Etna's "EtnaSettings" block is swapped for the one shared, writable buffer that follows the
 * settings menu (EtnaLook), and the pass gives it back instead of closing it when its chain goes.
 *
 * The light shafts pass also reads the shadow map and the terrain's own block, which only EtnaShadows
 * has: in Etna's passes an "EtnaShadow" block gets the terrain's buffer, and an "EtnaShadow" input, which
 * the chain points at main's depth as a stand-in, is swapped for the map (EtnaShadows.POST_MAP) before the
 * pass is first drawn. The pass's bindings were laid out from the chain's names, so they stay the same.
 * The same way, the last world pass's "EtnaMotes" block gets the fireflies' buffer (EtnaMotes), which it keeps
 * for the session.
 *
 * Vanilla also opens every pass by loading what its target held, because a pass may draw over part of it.
 * Every Etna pass draws every pixel of its target with no blending, so an Etna pass that writes one of
 * Etna's own targets clears it as it opens instead: on a tile-based GPU (Apple's) a clear on open costs
 * nothing, while a load reads the whole target from memory first. Passes that write Minecraft's picture
 * keep their load.
 */
@Mixin(PostPass.class)
public abstract class PostPassMixin {
	@Unique
	private static final Optional<Vector4fc> ETNA_CLEAR = Optional.of(new Vector4f(0.0F));

	@Shadow
	@Final
	private Map<String, GpuBuffer> customUniforms;

	/** True when this is an Etna pass that writes one of Etna's own targets. */
	@Unique
	private boolean etna$ownTarget;

	/** True when this pass holds EtnaShadows' terrain block, which it must not close. */
	@Unique
	private boolean etna$shadowBlock;

	/** True when this pass holds the fireflies' block (EtnaMotes), which it must not close. */
	@Unique
	private boolean etna$motesBlock;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void etna$sharedSettings(RenderPipeline pipeline, Identifier outputTargetId, Map<String, List<UniformValue>> uniformGroups,
			List<PostPass.Input> inputs, CallbackInfo ci) {
		GpuBuffer fixed = customUniforms.get(EtnaLook.BLOCK);
		if (fixed != null) {
			fixed.close();
			customUniforms.put(EtnaLook.BLOCK, EtnaLook.acquireBuffer());
		}
		// The pass's location is its chain's id plus its index, so the namespace says whose chain it is.
		boolean etna = pipeline.getLocation().getNamespace().equals(EtnaClient.MOD_ID);
		etna$ownTarget = etna && !outputTargetId.equals(PostChain.MAIN_TARGET_ID);
		if (etna) {
			GpuBuffer shadowFixed = customUniforms.get(EtnaShadows.BLOCK);
			if (shadowFixed != null) {
				shadowFixed.close();
				customUniforms.put(EtnaShadows.BLOCK, EtnaShadows.postBlock());
				etna$shadowBlock = true;
			}
			GpuBuffer motesFixed = customUniforms.get(EtnaMotes.BLOCK);
			if (motesFixed != null) {
				motesFixed.close();
				customUniforms.put(EtnaMotes.BLOCK, EtnaMotes.postBlock());
				etna$motesBlock = true;
			}
			// The pass keeps this same list (createPass makes it for the pass alone).
			inputs.replaceAll(input -> input.samplerName().equals(EtnaShadows.MAP_INPUT) ? EtnaShadows.POST_MAP : input);
		}
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void etna$releaseSettings(CallbackInfo ci) {
		// Out of the map before vanilla closes everything in it: other passes still read this buffer.
		GpuBuffer shared = customUniforms.remove(EtnaLook.BLOCK);
		if (shared != null) {
			EtnaLook.releaseBuffer(shared);
		}
		// The terrain's block stays EtnaShadows' own: out of the map, so vanilla doesn't close it.
		if (etna$shadowBlock) {
			customUniforms.remove(EtnaShadows.BLOCK);
			etna$shadowBlock = false;
		}
		// The fireflies' block stays EtnaMotes' own too.
		if (etna$motesBlock) {
			customUniforms.remove(EtnaMotes.BLOCK);
			etna$motesBlock = false;
		}
	}

	// The pass opens its target inside the lambda addToFrame hands the frame (javap names it). Should the
	// name ever differ, the passes keep their load: the picture is the same, the game starts all the same.
	@ModifyArg(
		method = "lambda$addToFrame$1",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;)Lcom/mojang/renderpearl/api/commands/RenderPass;"
		),
		index = 2,
		require = 0
	)
	private Optional<Vector4fc> etna$clearOnOpen(Optional<Vector4fc> clearColor) {
		return etna$ownTarget ? ETNA_CLEAR : clearColor;
	}
}
