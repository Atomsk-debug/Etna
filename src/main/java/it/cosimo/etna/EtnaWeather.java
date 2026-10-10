package it.cosimo.etna;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.resources.Identifier;

/**
 * Rain and snow in Etna's light: thinner, fainter rain that takes the sky's colour and glows orange by a
 * torch, softer snow, both leaning with the wind (assets/etna/shaders/core/weather.vsh and weather.fsh).
 *
 * WeatherEffectRenderer draws every rain and snow column through one private method, with vanilla's
 * WEATHER pipeline or, with Improved Transparency, one of its OIT_WEATHER stages. While Etna is on, the
 * terrain block is ready (EtnaShadows.lightBlock) and the rain look is Etna's, WeatherEffectRendererMixin
 * swaps that pipeline for a copy drawn with Etna's weather shader and binds the terrain block for it, as
 * EtnaClouds does for the clouds. The columns, their texture, their scrolling and the order they are drawn
 * in stay vanilla's. Etna off, or the rain look on Vanilla, vanilla's pipeline and shader draw them.
 *
 * A copy that does not compile leaves that pipeline vanilla's for the session; an error here leaves all
 * rain and snow vanilla's, never anything else.
 */
public final class EtnaWeather {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "core/weather");

	/** Bound to the copies: the terrain block, read through terrain_ubo.glsl. */
	public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
		.withUniform("EtnaShadow", UniformType.UNIFORM_BUFFER)
		.build();

	private static final Map<RenderPipeline, RenderPipeline> etnaPipelines = new IdentityHashMap<>();
	private static final Set<RenderPipeline> failedPipelines = new ReferenceOpenHashSet<>();
	/** Set after an error: vanilla's rain and snow for the rest of the session. */
	private static boolean broken;

	private EtnaWeather() {
	}

	/** Whether this frame's rain and snow are Etna's: Etna on, its terrain block written, the setting on Etna. */
	private static boolean active() {
		return !broken && EtnaConfig.rainLook == EtnaConfig.RainLook.ETNA && EtnaShadows.lightBlock() != null;
	}

	/**
	 * WeatherEffectRendererMixin, before the renderer sets its pipeline: binds the terrain block for Etna's
	 * copy. The block stays bound for the rest of the pass, where pipelines without it leave it unread.
	 */
	public static void bind(RenderPass pass) {
		GpuBuffer block = EtnaShadows.lightBlock();
		if (block != null && active()) {
			pass.setUniform("EtnaShadow", block.slice());
		}
	}

	/**
	 * WeatherEffectRendererMixin: the pipeline the renderer is about to draw rain and snow with, swapped for
	 * Etna's copy of it while active. The copy keeps everything of vanilla's (blending, depth test, vertex
	 * format, the Improved Transparency stage's defines and targets) but the shader and the terrain block.
	 */
	public static RenderPipeline pipelineFor(RenderPipeline vanilla) {
		if (!active() || failedPipelines.contains(vanilla)) {
			return vanilla;
		}
		try {
			RenderPipeline etna = etnaPipelines.computeIfAbsent(vanilla, p -> EtnaShadows.copy(p, true)
				.withVertexShader(SHADER)
				.withFragmentShader(SHADER)
				.withBindGroupLayout(LAYOUT)
				.withLocation(Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "weather/" + p.getLocation().getPath()))
				.build());
			if (RenderSystem.getCompiledPipelineNullable(etna) == null) {
				failedPipelines.add(vanilla);
				EtnaClient.LOGGER.error("Etna: {} did not compile, that rain and snow stay vanilla's", etna.getLocation());
				return vanilla;
			}
			return etna;
		} catch (RuntimeException e) {
			broken = true;
			EtnaClient.LOGGER.error("Etna: Etna's rain and snow failed, they are vanilla's for this session", e);
			return vanilla;
		}
	}

	/** EtnaFrame.resourcesReloaded: the shaders were recompiled, so a copy that failed gets another try. */
	static void resourcesReloaded() {
		failedPipelines.clear();
	}
}
