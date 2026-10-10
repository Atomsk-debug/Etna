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
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla's clouds lit by the sun, and their shadows on the land.
 *
 * Lit clouds: while Etna's terrain block is ready (EtnaShadows.lightBlock), CloudRenderer draws through a
 * copy of its pipeline with ETNA_CLOUD_LIGHT defined and that block bound (CloudRendererMixin), so
 * clouds.fsh reads the same sun direction, weather and look settings as the terrain. The geometry and the
 * drawing order stay vanilla's, in both of vanilla's ways of drawing clouds (into their own target with
 * Improved Transparency, straight into the world without). Etna off, the vanilla pipeline draws and
 * clouds.vsh and clouds.fsh are vanilla's shaders.
 *
 * Cloud shadows: the EtnaCloud field of the terrain block says where vanilla's clouds are this frame, and
 * the shadow-receiving terrain draws read vanilla's cloud texture (terrain_clouds.glsl). x is the height of
 * the middle of the cloud layer above the camera, in blocks (the flat sheet of fast clouds is at its
 * bottom). y and z are where the camera is on the cloud texture (u, v, 0 to 1), worked out exactly as
 * CloudRenderer.prepare works out where to draw the cells, so each shadow lies under its cloud and drifts
 * with it. w is the shadows' strength: the setting, scaled by how opaque vanilla makes the clouds, and 0
 * whenever vanilla draws no clouds (off in the video settings, or turned invisible) and in dimensions
 * without a sun.
 *
 * An error here turns only this off (vanilla's clouds and no cloud shadows from then on), never the rest
 * of Etna.
 */
public final class EtnaClouds {
	/** CloudRenderer's texture: one texel per cell, transparent where there is no cloud. */
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/environment/clouds.png");
	/** CloudRenderer's cells: 12 blocks wide, 4 high (fancy clouds); it moves them one cell per 400 ticks. */
	private static final double CELL = 12.0;
	private static final float FANCY_HEIGHT = 4.0F;
	private static final long TICKS_PER_CELL = 400L;
	/** The clouds' alpha in vanilla's overworld (0.8): opaque clouds as far as their shadows go. */
	private static final float VANILLA_ALPHA = 204.0F;

	/** Bound to the lit cloud pipelines: the terrain block, read through terrain_ubo.glsl. */
	public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
		.withUniform("EtnaShadow", UniformType.UNIFORM_BUFFER)
		.build();

	/** This frame's EtnaCloud; until the first frame, no shadows. */
	private static final Vector4f terrain = new Vector4f();
	/** The cloud texture as of this frame's update, or null when the shadows are off this frame. */
	private static @Nullable GpuTextureView texture;
	private static final Map<RenderPipeline, RenderPipeline> litPipelines = new IdentityHashMap<>();
	private static final Set<RenderPipeline> failedPipelines = new ReferenceOpenHashSet<>();
	/** Set after an error: vanilla's clouds and no cloud shadows for the rest of the session. */
	private static boolean broken;

	private EtnaClouds() {
	}

	/** Once per frame, before the terrain block is written (EtnaShadows.prepareFrame). */
	public static void update(LevelRenderState state, boolean hasSun) {
		terrain.set(0.0F, 0.0F, 0.0F, 0.0F);
		texture = null;
		if (broken) {
			return;
		}
		try {
			OptionsRenderState options = Minecraft.getInstance().gameRenderer.gameRenderState().optionsRenderState;
			int alpha = ARGB.alpha(state.cloudColor);
			float strength = EtnaConfig.cloudShadows.value;
			// The same test LevelRenderer.shouldRenderClouds makes: no clouds drawn, no shadows. A dimension
			// may also leave its clouds with no height at all.
			if (!hasSun || strength <= 0.0F || options.cloudStatus == CloudStatus.OFF || alpha == 0 || !Float.isFinite(state.cloudHeight)) {
				return;
			}
			GpuTextureView view = Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).textureView();
			int width = view.getWidth(0);
			int height = view.getHeight(0);
			if (width <= 0 || height <= 0) {
				return;
			}
			// CloudRenderer.prepare, step by step and with the same float and double arithmetic, so the shadows
			// stay under their clouds however long the world has run.
			Vec3 cam = state.cameraRenderState.pos;
			float cloudOffset = (float) (state.gameTime % (width * TICKS_PER_CELL)) + state.worldPartialTicks;
			double cloudX = cam.x + cloudOffset * 0.030000001F;
			double cloudZ = cam.z + 3.96F;
			double widthBlocks = width * CELL;
			double heightBlocks = height * CELL;
			cloudX -= Mth.floor(cloudX / widthBlocks) * widthBlocks;
			cloudZ -= Mth.floor(cloudZ / heightBlocks) * heightBlocks;
			float middle = options.cloudStatus == CloudStatus.FANCY ? 0.5F * FANCY_HEIGHT : 0.0F;
			terrain.set(
				(float) (state.cloudHeight + middle - cam.y),
				(float) (cloudX / widthBlocks),
				(float) (cloudZ / heightBlocks),
				strength * Math.min(alpha / VANILLA_ALPHA, 1.0F)
			);
			texture = view;
		} catch (RuntimeException e) {
			disable("the cloud shadows failed", e);
		}
	}

	/** The EtnaCloud vec4 of the terrain block, bytes 320 to 335. */
	public static Vector4f terrainInfo() {
		return terrain;
	}

	/**
	 * The texture for EtnaCloudSampler. With no cloud shadows this frame (EtnaCloud.w is 0, and the shader
	 * never reads it) the lightmap stands in: the terrain pipelines declare the sampler, so something has to
	 * be bound.
	 */
	public static GpuTextureView texture() {
		GpuTextureView view = texture;
		return view != null ? view : Minecraft.getInstance().gameRenderer.lightmap();
	}

	/**
	 * CloudRendererMixin, before CloudRenderer sets its pipeline: binds the terrain block for the lit
	 * pipeline. A pipeline without the block (vanilla's, Etna off) leaves it unread.
	 */
	public static void bind(RenderPass pass) {
		GpuBuffer block = EtnaShadows.lightBlock();
		if (block != null && !broken) {
			pass.setUniform("EtnaShadow", block.slice());
		}
	}

	/**
	 * CloudRendererMixin: the pipeline CloudRenderer is about to draw with (CLOUDS or FLAT_CLOUDS), swapped
	 * for its lit copy while the terrain block is ready. A copy that does not compile leaves that pipeline
	 * vanilla's for the session.
	 */
	public static RenderPipeline pipelineFor(RenderPipeline vanilla) {
		if (broken || EtnaShadows.lightBlock() == null || failedPipelines.contains(vanilla)) {
			return vanilla;
		}
		try {
			RenderPipeline lit = litPipelines.computeIfAbsent(vanilla, p -> EtnaShadows.copy(p, true)
				.withShaderDefine("ETNA_CLOUD_LIGHT")
				.withBindGroupLayout(LAYOUT)
				.withLocation(Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "lit/" + p.getLocation().getPath()))
				.build());
			if (RenderSystem.getCompiledPipelineNullable(lit) == null) {
				failedPipelines.add(vanilla);
				EtnaClient.LOGGER.error("Etna: {} did not compile, those clouds stay vanilla's", lit.getLocation());
				return vanilla;
			}
			return lit;
		} catch (RuntimeException e) {
			disable("the lit clouds failed", e);
			return vanilla;
		}
	}

	/** EtnaFrame.resourcesReloaded: the shaders were recompiled, so a lit copy that failed gets another try. */
	static void resourcesReloaded() {
		failedPipelines.clear();
	}

	private static void disable(String why, Throwable cause) {
		broken = true;
		terrain.set(0.0F, 0.0F, 0.0F, 0.0F);
		texture = null;
		EtnaClient.LOGGER.error("Etna: {}, clouds are vanilla's and cast no shadows for this session", why, cause);
	}
}
