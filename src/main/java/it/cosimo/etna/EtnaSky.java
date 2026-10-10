package it.cosimo.etna;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Etna's sky: in the overworld, with the camera in the open air, vanilla's sky disc, sunrise band, sun and
 * stars are replaced by one triangle over the screen that paints the sky from Etna's sky model
 * (assets/etna/shaders/core/sky.*, include/sky.glsl): the sky's gradient and colours for the sun's height,
 * the weather and the moon, the sun's disc and hashed stars. Vanilla's moon (its phases are in its texture)
 * and the sky occluder below the horizon stay. SkyRendererMixin hooks it in; vanilla's own sky draws
 * whenever Etna is off (F7), in the Nether and the End, under water, in lava and powder snow, under
 * Blindness or Darkness, in a boss's fog, or if the sky pipeline fails to compile.
 *
 * The same model, ported to Java (EtnaSkyModel), works out once a frame what the rest of Etna needs: the
 * colours of the sunlight and the skylight for the terrain, the per-frame part of the sky the terrain
 * needs to know the sky's colour in any direction (the distant land's fog, the water's reflection), and
 * vanilla's fog colour, so the fog matches the sky straight ahead.
 *
 * The sun path's tilt lives here too: the sun, the moon and the stars turn about a tilted axis, by
 * EtnaConfig.sunPathTilt degrees towards the south, so at noon the sun stands south of overhead, walls
 * facing south catch it and shadows fall to the north. EtnaShadows lights the land from the same
 * direction (bodyDirection), so the shadows always follow the sun on screen.
 */
public final class EtnaSky {
	public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
		.withUniform("EtnaSky", UniformType.UNIFORM_BUFFER)
		.build();
	/** std140: six vec4s, the EtnaSky block in assets/etna/shaders/include/sky_ubo.glsl. */
	private static final int UBO_SIZE = 6 * 16;
	// Same flags vanilla uses for its projection UBO: uniform buffer, writable by the encoder.
	private static final int UBO_USAGE = 136;
	/** The sky's horizon straight ahead sets vanilla's fog colour: this far up (about 2 degrees). */
	private static final float FOG_ELEVATION = 0.035F;
	/** The moon's brightness by MoonPhase.index(): full, waning gibbous ... new ... waxing gibbous. */
	private static final float[] MOON_PHASE_LIGHT = {1.0F, 0.8F, 0.55F, 0.3F, 0.12F, 0.3F, 0.55F, 0.8F};

	private static final EtnaSkyModel.Frame frame = new EtnaSkyModel.Frame();
	private static final float[] sun = new float[3];
	private static final float[] moon = new float[3];
	private static final float[] sunlight = new float[3];
	private static final float[] skylight = new float[3];
	private static final float[] fogDir = new float[3];
	private static final float[] fogColor = new float[3];
	/** The sky's horizon averaged all round (EtnaAir's mist colour), while Etna draws the sky. */
	private static final float[] horizon = new float[3];
	private static final Vector3f scratch = new Vector3f();
	private static final Matrix3f stars = new Matrix3f();
	private static float weather;
	private static float moonLight;
	private static float glow = 1.0F;
	private static float starBrightness;
	private static float sunDisc;
	/** The model describes this frame's sky: Etna on, an overworld sky. */
	private static boolean modelReady;
	/** Etna draws the sky this frame (decided before the sky is drawn, in beforeFogUpload). */
	private static boolean drawSky;
	/** Inside SkyRenderer.render while Etna's sky replaces vanilla's. */
	private static boolean replacing;

	private static @Nullable RenderPipeline pipeline;
	private static @Nullable RenderPipeline pipelineWithDepth;
	private static @Nullable GpuBuffer ubo;
	/** The model failed: vanilla's sky and 0.6.0's light colours for the rest of the session. */
	private static boolean failed;
	/** Only the sky's drawing failed (its pipeline or its block): the model still lights the land. */
	private static boolean drawFailed;

	private EtnaSky() {
	}

	/** The sun path's tilt in radians: EtnaConfig.sunPathTilt while Etna is on, 0 (vanilla's path) while it is off. */
	public static float tilt() {
		return EtnaConfig.enabled ? (float) Math.toRadians(EtnaConfig.sunPathTilt) : 0.0F;
	}

	/**
	 * Direction towards a body of the sky (the sun, the moon) at vanilla's angle: the rotation vanilla's
	 * sky applies to the sun quad (up, turned by the angle about the east-west axis, turned to the east),
	 * then the tilt of the path about the east-west axis, towards the south (+z).
	 */
	public static Vector3f bodyDirection(float angle, Vector3f dest) {
		return dest.set(0.0F, 1.0F, 0.0F).rotateX(angle).rotateY((float) (-Math.PI / 2.0)).rotateX(tilt());
	}

	/**
	 * Every frame, from GameRendererWaterMixin right before vanilla uploads its fog, once the sky has been
	 * extracted: works out this frame's sky, decides whether Etna draws it and, if so, sets vanilla's fog
	 * colour to the sky's horizon straight ahead.
	 */
	public static void beforeFogUpload(LevelRenderState state) {
		modelReady = false;
		drawSky = false;
		if (!EtnaConfig.enabled || failed) {
			return;
		}
		try {
			update(state);
		} catch (RuntimeException e) {
			failed = true;
			modelReady = false;
			drawSky = false;
			EtnaClient.LOGGER.error("Etna: the sky failed, vanilla's sky is back for this session", e);
		}
	}

	private static void update(LevelRenderState state) {
		SkyRenderState sky = state.skyRenderState;
		if (sky.skybox != DimensionType.Skybox.OVERWORLD) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float rain = Math.clamp(1.0F - sky.rainBrightness, 0.0F, 1.0F);
		float thunder = level != null ? Math.clamp(level.getThunderLevel(partialTick), 0.0F, 1.0F) : 0.0F;
		weather = rain + Math.min(thunder, rain);
		moonLight = MOON_PHASE_LIGHT[Math.floorMod(sky.moonPhase.index(), MOON_PHASE_LIGHT.length)];
		// The Sky Glow setting: 1 (the look as tuned) at its default of 60 %, half of it at 0.
		glow = 0.5F + EtnaConfig.skyGlow.value / 1.2F;
		starBrightness = 2.0F * sky.starBrightness * sky.rainBrightness;
		sunDisc = Math.clamp(sky.rainBrightness, 0.0F, 1.0F);
		bodyDirection(sky.sunAngle, scratch);
		sun[0] = scratch.x;
		sun[1] = scratch.y;
		sun[2] = scratch.z;
		bodyDirection(sky.moonAngle, scratch);
		moon[0] = scratch.x;
		moon[1] = scratch.y;
		moon[2] = scratch.z;
		stars.identity().rotateX(tilt()).rotateY((float) (-Math.PI / 2.0)).rotateX(sky.starAngle);

		EtnaSkyModel.frame(frame, sun, moon, weather, moonLight, glow);
		if (sun[1] >= 0.0F) {
			EtnaSkyModel.sunlight(sun[1], sunlight);
		} else {
			System.arraycopy(EtnaSkyModel.MOONLIGHT, 0, sunlight, 0, 3);
		}
		EtnaSkyModel.skylight(frame, weather, skylight);
		modelReady = true;

		CameraRenderState camera = state.cameraRenderState;
		FogData fog = camera.fogData;
		boolean inAir = camera.fogType == FogType.NONE || camera.fogType == FogType.ATMOSPHERIC;
		if (!inAir || camera.entityRenderState.doesMobEffectBlockSky || fog.shouldCreateBossFog || drawFailed || !pipelinesReady()) {
			return;
		}
		drawSky = true;
		// Vanilla's fog colour: the sky's horizon in the direction the camera faces, as vanilla's own fog
		// colour follows the camera towards the sunrise. The terrain works out the horizon behind each
		// distant pixel itself; entities, clouds and the sky below the horizon use this one.
		Vector3f forward = camera.viewRotationMatrix.positiveZ(scratch).negate();
		if (forward.x * forward.x + forward.z * forward.z < 1e-6F) {
			// Straight up or down: the top of the screen faces the way to look.
			camera.viewRotationMatrix.positiveY(forward);
		}
		float flat = (float) Math.sqrt(forward.x * forward.x + forward.z * forward.z);
		if (flat > 1e-6F) {
			float side = (float) Math.sqrt(1.0F - FOG_ELEVATION * FOG_ELEVATION) / flat;
			fogDir[0] = forward.x * side;
			fogDir[1] = FOG_ELEVATION;
			fogDir[2] = forward.z * side;
			EtnaSkyModel.color(frame, fogDir, fogColor);
			fog.color.set(fogColor[0], fogColor[1], fogColor[2], 1.0F);
		}
		// The mist's colour: the same horizon, averaged over eight directions all round, so the mist keeps
		// its colour as the camera turns (the sun's own glow in it comes from the air's phase function).
		float side = (float) Math.sqrt(1.0F - FOG_ELEVATION * FOG_ELEVATION);
		horizon[0] = 0.0F;
		horizon[1] = 0.0F;
		horizon[2] = 0.0F;
		for (int i = 0; i < 8; i++) {
			double az = (i + 0.5) * Math.PI / 4.0;
			fogDir[0] = (float) Math.cos(az) * side;
			fogDir[1] = FOG_ELEVATION;
			fogDir[2] = (float) Math.sin(az) * side;
			EtnaSkyModel.color(frame, fogDir, fogColor);
			for (int k = 0; k < 3; k++) {
				horizon[k] += fogColor[k] / 8.0F;
			}
		}
	}

	/** EtnaFrame.resourcesReloaded: the shaders were recompiled, so a sky pipeline that failed gets another try. */
	static void resourcesReloaded() {
		drawFailed = false;
	}

	/** Builds the sky's two pipelines on first use and checks they compiled; false (until a reload) if they did not. */
	private static boolean pipelinesReady() {
		try {
			if (pipeline == null || pipelineWithDepth == null) {
				pipeline = build(RenderPipelines.SKY, "pipeline/sky");
				pipelineWithDepth = build(RenderPipelines.SKY_WITH_DEPTH_ATTACHMENT, "pipeline/sky_with_depth_attachment");
			}
			if (RenderSystem.getCompiledPipelineNullable(pipeline) != null && RenderSystem.getCompiledPipelineNullable(pipelineWithDepth) != null) {
				return true;
			}
			EtnaClient.LOGGER.error("Etna: the sky pipeline did not compile, vanilla's sky stays");
		} catch (RuntimeException e) {
			EtnaClient.LOGGER.error("Etna: could not build the sky pipeline, vanilla's sky stays", e);
		}
		drawFailed = true;
		return false;
	}

	/**
	 * Etna's sky pipeline in the place of one of vanilla's two sky disc pipelines: the same colour target,
	 * depth state and depth format (the one drawn into the separate sky target with improved transparency
	 * has no depth, the one drawn first in the main pass does not test or write it), with the bindings of
	 * vanilla's stars (globals, projection, the model-view matrix) and Etna's sky block, one triangle over
	 * the screen with no vertex buffer, as vanilla's sky occluder.
	 */
	private static RenderPipeline build(RenderPipeline vanilla, String path) {
		List<BindGroupLayout> layouts = new ArrayList<>(RenderPipelines.STARS.getBindGroupLayouts());
		layouts.add(LAYOUT);
		ColorTargetState[] colors = vanilla.getColorTargetStates().toArray(new ColorTargetState[0]);
		RenderPipeline occluder = RenderPipelines.SKY_OCCLUDER;
		RenderPipeline.Snippet snippet = new RenderPipeline.Snippet(
			occluder.getShaders(),
			Optional.empty(),
			Optional.of(layouts),
			colors,
			colors.length,
			Optional.ofNullable(vanilla.getDepthStencilState()),
			Optional.of(vanilla.getDepthStencilFormat()),
			Optional.of(occluder.getPolygonMode()),
			Optional.of(occluder.isCull()),
			new VertexFormat[0],
			Optional.of(occluder.getPrimitiveTopology()),
			0
		);
		Identifier shader = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "core/sky");
		return RenderPipeline.builder(snippet)
			.withVertexShader(shader)
			.withFragmentShader(shader)
			.withLocation(Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, path))
			.build();
	}

	/** SkyRenderer.prepare, before the sky's render pass opens: uploads the sky block. */
	public static void prepare() {
		if (!drawSky) {
			return;
		}
		try {
			if (ubo == null) {
				ubo = RenderSystem.getDevice().createBuffer(() -> "Etna sky UBO", UBO_USAGE, UBO_SIZE);
			}
			float seconds = (float) ((System.nanoTime() / 1.0e9) % 3600.0);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, UBO_SIZE)
					.putVec4(new Vector4f(sun[0], sun[1], sun[2], weather))
					.putVec4(new Vector4f(moon[0], moon[1], moon[2], moonLight))
					.putVec4(new Vector4f(starBrightness, sunDisc, seconds, glow))
					.putVec4(new Vector4f(stars.m00(), stars.m01(), stars.m02(), 0.0F))
					.putVec4(new Vector4f(stars.m10(), stars.m11(), stars.m12(), 0.0F))
					.putVec4(new Vector4f(stars.m20(), stars.m21(), stars.m22(), 0.0F));
				RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), builder.get());
			}
		} catch (RuntimeException e) {
			drawFailed = true;
			drawSky = false;
			EtnaClient.LOGGER.error("Etna: could not upload the sky, vanilla's sky is back for this session", e);
		}
	}

	/** SkyRenderer.render begins: whether Etna's sky replaces vanilla's this time. */
	public static void beginRender(SkyRenderState state) {
		replacing = drawSky && ubo != null && state.skybox == DimensionType.Skybox.OVERWORLD;
	}

	public static void endRender() {
		replacing = false;
	}

	/** Whether vanilla's sky disc, sunrise band, sun and stars stand down for Etna's sky right now. */
	public static boolean replacing() {
		return replacing;
	}

	/** In place of vanilla's sky disc: Etna's whole sky. False leaves the disc to vanilla. */
	public static boolean drawSky(RenderPass pass, boolean withDepthAttachment) {
		if (!replacing) {
			return false;
		}
		RenderPipeline p = withDepthAttachment ? pipelineWithDepth : pipeline;
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F));
		pass.pushDebugGroup(() -> "Etna sky");
		pass.setPipeline(RenderSystem.getCompiledPipeline(p));
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("EtnaSky", ubo.slice());
		pass.draw(3, 1, 0, 0);
		pass.popDebugGroup();
		return true;
	}

	/**
	 * SkyRenderer.renderSunMoonAndStars, before it turns its pose to the east: the tilt of the sun path, so
	 * vanilla's moon (and its sun and stars where vanilla's sky draws) follow the light's direction.
	 */
	public static void tiltPose(PoseStack poseStack) {
		float tilt = tilt();
		if (tilt != 0.0F) {
			poseStack.rotate(Axis.XP, tilt);
		}
	}

	/** Whether the model describes this frame's sky (Etna on, an overworld sky). */
	public static boolean modelReady() {
		return modelReady;
	}

	/** Whether Etna draws the sky this frame. */
	public static boolean drawsSky() {
		return drawSky;
	}

	/**
	 * EtnaShadows.writeUbo: the sky's fields of the terrain block. Bytes 288-319 (EtnaSky0, EtnaSky1), then
	 * the cloud shadows' EtnaCloud at 320-335 (EtnaClouds.terrainInfo, which doesn't need the model), then
	 * 336-431 (EtnaSkySunLow .. EtnaSkyOvercast). The sky's fields are all 0 without a model (Etna off, no
	 * overworld sky): the terrain keeps 0.6.0's colours there.
	 */
	public static void writeTerrain(Std140Builder builder) {
		if (!modelReady) {
			builder.putVec4(new Vector4f(0.0F))
				.putVec4(new Vector4f(0.0F))
				.putVec4(EtnaClouds.terrainInfo());
			for (int i = 0; i < 6; i++) {
				builder.putVec4(new Vector4f(0.0F));
			}
			return;
		}
		EtnaSkyModel.Frame f = frame;
		builder.putVec4(new Vector4f(sunlight[0], sunlight[1], sunlight[2], weather))
			.putVec4(new Vector4f(skylight[0], skylight[1], skylight[2], moonLight))
			.putVec4(EtnaClouds.terrainInfo())
			.putVec4(new Vector4f(f.sunLow[0], f.sunLow[1], f.sunLow[2], f.rain))
			.putVec4(new Vector4f(f.sunHigh[0], f.sunHigh[1], f.sunHigh[2], f.storm))
			.putVec4(new Vector4f(f.multi[0], f.multi[1], f.multi[2], f.glow))
			.putVec4(new Vector4f(f.moonLow[0], f.moonLow[1], f.moonLow[2], 0.0F))
			.putVec4(new Vector4f(f.moonHigh[0], f.moonHigh[1], f.moonHigh[2], 0.0F))
			.putVec4(new Vector4f(f.overcast[0], f.overcast[1], f.overcast[2], drawSky ? 1.0F : 0.0F));
	}

	/** EtnaLook's Shade.yzw: the skylight's colour while Etna draws the sky (the air's blue in the world pass), else 0. */
	public static Vector4f postShade(float ambientOcclusion) {
		// One allocation, not one per branch: see EtnaShadows.NO_MAP for the compiler crash such merges caused.
		float on = drawSky ? 1.0F : 0.0F;
		return new Vector4f(ambientOcclusion, skylight[0] * on, skylight[1] * on, skylight[2] * on);
	}

	/**
	 * EtnaAir: the colour of the mist while Etna draws the sky, the sky's horizon averaged all round, into
	 * out's rgb; false (out unchanged) otherwise.
	 */
	public static boolean mistColor(Vector4f out) {
		if (!drawSky) {
			return false;
		}
		out.set(horizon[0], horizon[1], horizon[2], out.w);
		return true;
	}

	/**
	 * EtnaAir: the colour of the direct light as the terrain gets it (EtnaSky0: the sun's, or the moon's at
	 * night, luma 1) with w 1 while the model is ready; all 0 otherwise.
	 */
	public static void airLight(Vector4f out) {
		if (modelReady) {
			out.set(sunlight[0], sunlight[1], sunlight[2], 1.0F);
		} else {
			out.set(0.0F);
		}
	}

	/** The moon's brightness by its phase this frame (0 new .. 1 full), 1 without a model. */
	public static float moonLight() {
		return modelReady ? moonLight : 1.0F;
	}
}
