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
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Etna's sky: in the overworld, with the camera in the open air, vanilla's sky disc, sunrise band, sun and
 * stars are replaced by one triangle over the screen that paints the sky from Etna's sky model
 * (assets/etna/shaders/core/sky.*, include/sky.glsl): the sky's gradient and colours for the sun's height,
 * the weather and the moon, the sun's disc, and the night sky's wonders (the Wonders settings page): hashed
 * stars, the Milky Way with its dust lanes, rare shooting stars, the aurora over cold biomes, a round moon
 * drawn with its phase (in place of vanilla's, which stands down while Round Moon is on) with a halo on
 * cold clear nights, and rainbows after rain. The sky occluder below the horizon stays vanilla's. In the
 * End, the same triangle paints Etna's End sky (a violet nebula, small dense stars and the End's flash)
 * in place of vanilla's box. SkyRendererMixin hooks it in; vanilla's own sky draws whenever Etna is off
 * (F7), in the Nether, under water, in lava and powder snow, under Blindness or Darkness, in a boss's fog
 * (the overworld), with the End Sky setting off, or if the sky pipeline fails to compile.
 *
 * The sky's details come from a small tiling noise texture (assets/etna/textures/environment/sky_noise.png,
 * made by tools/gen_sky_noise.py): one bilinear read in place of an octave of noise worked out per pixel.
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
		.withUniform("EtnaSkyNoise", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();
	/** std140: thirteen vec4s, the EtnaSky block in assets/etna/shaders/include/sky_ubo.glsl. */
	private static final int UBO_SIZE = 13 * 16;
	// Same flags vanilla uses for its projection UBO: uniform buffer, writable by the encoder.
	private static final int UBO_USAGE = 136;
	/** The sky's horizon straight ahead sets vanilla's fog colour: this far up (about 2 degrees). */
	private static final float FOG_ELEVATION = 0.035F;
	/** The moon's brightness by MoonPhase.index(): full, waning gibbous ... new ... waxing gibbous. */
	private static final float[] MOON_PHASE_LIGHT = {1.0F, 0.8F, 0.55F, 0.3F, 0.12F, 0.3F, 0.55F, 0.8F};
	/** The sky's noise, four independent tiling channels (tools/gen_sky_noise.py). */
	private static final Identifier NOISE = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "textures/environment/sky_noise.png");
	/** The longest frame step the eases below take, in seconds, so a hitch doesn't jump them. */
	private static final float MAX_STEP = 0.25F;
	/**
	 * How cold the camera's biome is, from its base temperature: fully cold at COLD_FULL and below (snowy
	 * plains and ice spikes are 0, snowy taiga -0.5, frozen peaks -0.7), not at all from COLD_NONE up
	 * (old growth pine taiga 0.3, plains 0.8); taiga (0.25) is a little cold. It eases over about 8 seconds
	 * (three time constants), so walking out of a snowy biome fades the aurora instead of cutting it.
	 */
	private static final float COLD_FULL = -0.05F;
	private static final float COLD_NONE = 0.35F;
	private static final float COLD_EASE = 8.0F / 3.0F;
	/** The aurora's own ease (time constant, seconds), over the day's turn and the weather. */
	private static final float AURORA_EASE = 2.0F;
	/** A full moon brings this much aurora to biomes that aren't cold (Aurora: Cold Biomes and Full Moon). */
	private static final float FULL_MOON_AURORA = 0.6F;
	/** The aurora's light on the land, luma 1: its green with a little of its violet (EtnaNight.rgb). */
	private static final float[] AURORA_LAND = {0.31F, 1.23F, 0.80F};
	/** How long the sky remembers a rain for its rainbow: the memory falls to a third in this many seconds. */
	private static final float RAIN_MEMORY = 100.0F;
	/** The strongest rainbow (SkyRainbow.x), just after the rain with the sun low: subtle, never a cartoon. */
	private static final float RAINBOW_MAX = 0.85F;
	/**
	 * Shooting stars by the setting (Off, Rare, Frequent): seconds between chances and the chance each
	 * time, about one every two minutes or one every 24 seconds. Both periods divide the hour the sky's
	 * clock wraps at, so the wrap never cuts one short.
	 */
	private static final float[] SHOOT_PERIOD = {0.0F, 40.0F, 12.0F};
	private static final float[] SHOOT_CHANCE = {0.0F, 0.3F, 0.5F};
	/**
	 * Biome Sky Colour: vanilla's sky colour for the biome (SkyRenderState.skyColor) against its plains sky
	 * (0x78A7FF), both linear and luma 1, raised to the setting times TINT_GAIN, tints the sunlit air. Each
	 * channel stays within TINT_LIMIT of 1, and a paler biome sky leans less, so a grey one (the pale
	 * garden) leaves Etna's blue alone instead of turning it peach: a multiplier can't take colour out.
	 * Biome edges are blended by vanilla's probe and eased here over about 3 seconds (TINT_EASE is the
	 * time constant).
	 */
	private static final float TINT_GAIN = 0.6F;
	private static final float TINT_LIMIT = 0.15F;
	private static final float TINT_EASE = 1.0F;
	private static final float[] PLAINS_SKY = linearHue(0x78 / 255.0F, 0xA7 / 255.0F, 0xFF / 255.0F, new float[3]);

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
	/** Etna draws the End sky this frame (decided in beforeFogUpload, as drawSky). */
	private static boolean drawEnd;
	/** Inside SkyRenderer.render while Etna's End sky replaces vanilla's. */
	private static boolean replacingEnd;

	// The night sky's state, eased from frame to frame (render thread only).
	private static long lastNanos;
	/** The level the eases below were for: another one (a new world, another dimension) starts them afresh. */
	private static int levelId;
	private static float coldness;
	/** The camera's biome temperature couldn't be read: no biome is cold for the rest of the session. */
	private static boolean coldBroken;
	/** Vanilla's sky colour for the biome, linear and luma 1, as of the last clear day; plains until then. */
	private static final float[] biomeHue = PLAINS_SKY.clone();
	private static final float[] biomeScratch = new float[3];
	private static final float[] tint = {1.0F, 1.0F, 1.0F};
	private static final float[] tintTarget = new float[3];
	private static float rainMemory;
	private static float aurora;
	private static float rainbow;
	/** The moon's phase as an angle (SkyMoonLook.y): 0 full, pi new. */
	private static float moonPhase;
	/** Towards the End's flash, and its strength (SkyEnd). */
	private static final Vector4f endFlash = new Vector4f();

	private static @Nullable RenderPipeline pipeline;
	private static @Nullable RenderPipeline pipelineWithDepth;
	private static @Nullable GpuBuffer ubo;
	private static @Nullable GpuTextureView noise;
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
		drawEnd = false;
		if (!EtnaConfig.enabled || failed) {
			return;
		}
		try {
			update(state);
			updateEnd(state);
		} catch (RuntimeException e) {
			failed = true;
			modelReady = false;
			drawSky = false;
			drawEnd = false;
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
		updateNight(sky, level, state.cameraRenderState.blockPos, rain);

		EtnaSkyModel.frame(frame, sun, moon, weather, moonLight, glow);
		// The biome's tint on the sunlit air, as sky.vsh tints the shader's own frame: the land's skylight,
		// its distant fog and vanilla's fog colour all follow it.
		for (int k = 0; k < 3; k++) {
			frame.sunLow[k] *= tint[k];
			frame.sunHigh[k] *= tint[k];
			frame.multi[k] *= tint[k];
		}
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

	/**
	 * The night sky's wonders for this frame, once the sun, the moon and the weather are known: how cold the
	 * camera's biome is, the biome's tint, the rain's memory, and from them the aurora and the rainbow.
	 */
	private static void updateNight(SkyRenderState sky, @Nullable ClientLevel level, BlockPos camera, float rain) {
		long now = System.nanoTime();
		float step = lastNanos == 0L ? 0.0F : Math.clamp((now - lastNanos) / 1.0e9F, 0.0F, MAX_STEP);
		lastNanos = now;
		if (Minecraft.getInstance().isPaused()) {
			step = 0.0F;
		}
		int id = System.identityHashCode(level);
		boolean snap = id != levelId;
		levelId = id;

		int phase = Math.floorMod(sky.moonPhase.index(), MOON_PHASE_LIGHT.length);
		moonPhase = phase * (float) (Math.PI / 4.0);
		// How clear the sky is, squared: even a light rain hides most of the aurora and the rainbow.
		float clear = 1.0F - Math.min(weather, 1.0F);
		clear *= clear;

		coldness = ease(coldness, coldTarget(level, camera), COLD_EASE, step, snap);

		// Vanilla's sky colour is darkened at night and greyed by rain: its hue is read from clear daytime
		// skies only (and on arrival), and kept in between.
		Vector3fc skyColor = sky.skyColor;
		if (skyColor != null && (snap || sun[1] > 0.1F && weather < 0.05F)) {
			linearHue(skyColor.x(), skyColor.y(), skyColor.z(), biomeScratch);
			if (Float.isFinite(biomeScratch[0] + biomeScratch[1] + biomeScratch[2])) {
				System.arraycopy(biomeScratch, 0, biomeHue, 0, 3);
			}
		}
		// A paler biome sky leans less: a grey one (the pale garden) would only warm the blue towards peach.
		float strength = EtnaConfig.biomeSkyColor.value * TINT_GAIN * Math.min(saturation(biomeHue) / saturation(PLAINS_SKY), 1.0F);
		for (int k = 0; k < 3; k++) {
			tintTarget[k] = (float) Math.pow(biomeHue[k] / PLAINS_SKY[k], strength);
		}
		normalizeLuma(tintTarget);
		for (int k = 0; k < 3; k++) {
			tintTarget[k] = Math.clamp(tintTarget[k], 1.0F - TINT_LIMIT, 1.0F + TINT_LIMIT);
		}
		normalizeLuma(tintTarget);
		for (int k = 0; k < 3; k++) {
			tint[k] = ease(tint[k], tintTarget[k], TINT_EASE, step, snap);
		}

		// The rain's memory: up at once with the rain, then slowly down once it has stopped.
		rainMemory = Math.max(rain, rainMemory * (float) Math.exp(-step / RAIN_MEMORY));
		if (rainMemory < 1.0e-3F) {
			rainMemory = 0.0F;
		}

		float condition = switch (EtnaConfig.aurora) {
			case OFF -> 0.0F;
			case COLD -> coldness;
			case COLD_FULL_MOON -> Math.max(coldness, phase == 0 ? FULL_MOON_AURORA : 0.0F);
			case EVERY_NIGHT -> 1.0F;
		};
		// Only in the dark: from the end of dusk (the sun 3 degrees down) to full night (14 degrees down).
		float night = 1.0F - EtnaSkyModel.smoothstep(-0.25F, -0.05F, sun[1]);
		aurora = ease(aurora, condition * night * clear, AURORA_EASE, step, snap);

		// A rainbow needs the sun behind you and low (the bow stands 42 degrees from the point opposite
		// it, so with the sun higher than that it is under the horizon), and is brightest with it low.
		float elevation = (float) Math.toDegrees(Math.asin(Math.clamp(sun[1], -1.0F, 1.0F)));
		float window = EtnaSkyModel.smoothstep(-1.0F, 4.0F, elevation) * (1.0F - EtnaSkyModel.smoothstep(30.0F, 42.0F, elevation));
		float wet = switch (EtnaConfig.rainbows) {
			case OFF -> 0.0F;
			case AFTER_RAIN -> rainMemory;
			case ALWAYS -> 1.0F;
		};
		rainbow = RAINBOW_MAX * wet * window * clear;
	}

	/** How cold the biome at the camera is (0 .. 1, before easing); 0 when it can't be read. */
	private static float coldTarget(@Nullable ClientLevel level, BlockPos camera) {
		if (level == null || coldBroken) {
			return 0.0F;
		}
		try {
			float temperature = level.getBiome(camera).value().getBaseTemperature();
			return 1.0F - EtnaSkyModel.smoothstep(COLD_FULL, COLD_NONE, temperature);
		} catch (RuntimeException e) {
			coldBroken = true;
			EtnaClient.LOGGER.warn("Etna: could not read the biome's temperature, the aurora treats every biome as warm", e);
			return 0.0F;
		}
	}

	/** Moves value towards target by this frame's step, with time constant tau in seconds; snap jumps there. */
	private static float ease(float value, float target, float tau, float step, boolean snap) {
		if (snap) {
			return target;
		}
		float next = value + (target - value) * (1.0F - (float) Math.exp(-step / tau));
		// Settle on the target once within a ten-thousandth: a faded effect reaches exactly 0, so its shader
		// branch is skipped, instead of creeping towards it (and sticking at a denormal) for good.
		return Math.abs(target - next) < 1.0e-4F ? target : next;
	}

	/** A display colour (0 .. 1 sRGB) made linear and scaled to luma 1, into out; zeros for black. */
	private static float[] linearHue(float r, float g, float b, float[] out) {
		out[0] = linear(r);
		out[1] = linear(g);
		out[2] = linear(b);
		normalizeLuma(out);
		return out;
	}

	/** How colourful a colour is: 0 grey, 1 a pure primary ((max - min) / max). */
	private static float saturation(float[] c) {
		float max = Math.max(c[0], Math.max(c[1], c[2]));
		float min = Math.min(c[0], Math.min(c[1], c[2]));
		return max > 1e-4F ? (max - min) / max : 0.0F;
	}

	private static float linear(float c) {
		c = Math.clamp(c, 0.0F, 1.0F);
		return c <= 0.04045F ? c / 12.92F : (float) Math.pow((c + 0.055F) / 1.055F, 2.4F);
	}

	private static void normalizeLuma(float[] c) {
		float l = EtnaSkyModel.luma(c);
		float scale = l > 1e-4F ? 1.0F / l : 0.0F;
		for (int k = 0; k < 3; k++) {
			c[k] *= scale;
		}
	}

	/**
	 * In the End, with the End Sky setting on and the camera in the open air: Etna draws the End's sky
	 * this frame, with the End's flash where vanilla puts it. The overworld's model stays off (modelReady).
	 */
	private static void updateEnd(LevelRenderState state) {
		SkyRenderState sky = state.skyRenderState;
		if (sky.skybox != DimensionType.Skybox.END || !EtnaConfig.endSky) {
			return;
		}
		CameraRenderState camera = state.cameraRenderState;
		boolean inAir = camera.fogType == FogType.NONE || camera.fogType == FogType.ATMOSPHERIC;
		if (!inAir || camera.entityRenderState.doesMobEffectBlockSky || drawFailed || !pipelinesReady()) {
			return;
		}
		drawEnd = true;
		// SkyRenderer.renderEndFlash: the flash quad straight up, turned by -90 - x degrees about the east-west
		// axis, then by 180 - y about the vertical.
		float intensity = sky.endFlashIntensity > 1.0E-5F ? Math.clamp(sky.endFlashIntensity, 0.0F, 1.0F) : 0.0F;
		scratch.set(0.0F, 1.0F, 0.0F)
			.rotateX((float) Math.toRadians(-90.0F - sky.endFlashXAngle))
			.rotateY((float) Math.toRadians(180.0F - sky.endFlashYAngle));
		endFlash.set(scratch.x, scratch.y, scratch.z, intensity);
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
	 * vanilla's stars (globals, projection, the model-view matrix) and Etna's sky block and noise, one
	 * triangle over the screen with no vertex buffer, as vanilla's sky occluder. The End's sky draws through
	 * the same two: vanilla draws its End sky box in the same passes, with the same targets.
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

	/**
	 * SkyRenderer.prepare, before the sky's render pass opens: uploads the sky block, and asks for the
	 * noise texture (loaded on the first ask) while no pass is open.
	 */
	public static void prepare() {
		if (!drawSky && !drawEnd) {
			return;
		}
		try {
			if (ubo == null) {
				ubo = RenderSystem.getDevice().createBuffer(() -> "Etna sky UBO", UBO_USAGE, UBO_SIZE);
			}
			noise = Minecraft.getInstance().getTextureManager().getTexture(NOISE).textureView();
			float seconds = (float) ((System.nanoTime() / 1.0e9) % 3600.0);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, UBO_SIZE);
				if (drawSky) {
					writeOverworld(builder, seconds);
				} else {
					writeEnd(builder, seconds);
				}
				RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), builder.get());
			}
		} catch (RuntimeException e) {
			drawFailed = true;
			drawSky = false;
			drawEnd = false;
			EtnaClient.LOGGER.error("Etna: could not upload the sky, vanilla's sky is back for this session", e);
		}
	}

	/** The sky block for the overworld, field by field as sky_ubo.glsl lists them. */
	private static void writeOverworld(Std140Builder builder, float seconds) {
		EtnaConfig.NightSky night = EtnaConfig.nightSky;
		boolean milkyWay = night == EtnaConfig.NightSky.MILKY_WAY || night == EtnaConfig.NightSky.NEBULA;
		int shoot = EtnaConfig.shootingStars.ordinal();
		builder.putVec4(new Vector4f(sun[0], sun[1], sun[2], weather))
			.putVec4(new Vector4f(moon[0], moon[1], moon[2], moonLight))
			.putVec4(new Vector4f(starBrightness, sunDisc, seconds, glow))
			.putVec4(new Vector4f(stars.m00(), stars.m01(), stars.m02(), 0.0F))
			.putVec4(new Vector4f(stars.m10(), stars.m11(), stars.m12(), 0.0F))
			.putVec4(new Vector4f(stars.m20(), stars.m21(), stars.m22(), 0.0F))
			.putVec4(new Vector4f(
				night != EtnaConfig.NightSky.OFF ? 1.0F : 0.0F,
				milkyWay ? 1.0F : 0.0F,
				night == EtnaConfig.NightSky.NEBULA ? 1.0F : 0.0F,
				0.0F
			))
			.putVec4(new Vector4f(SHOOT_PERIOD[shoot], SHOOT_CHANCE[shoot], 0.0F, 0.0F))
			.putVec4(new Vector4f(aurora, coldness, 0.0F, 0.0F))
			.putVec4(new Vector4f(EtnaConfig.roundMoon ? 1.0F : 0.0F, moonPhase, 0.0F, 0.0F))
			.putVec4(new Vector4f(rainbow, 0.0F, 0.0F, 0.0F))
			.putVec4(new Vector4f(tint[0], tint[1], tint[2], 0.0F))
			.putVec4(new Vector4f(0.0F));
	}

	/**
	 * The sky block for the End: SkyTint.w 1 draws the End sky, SkyEnd places the flash, and the clock
	 * drifts the nebula and twinkles the stars. The overworld's fields are set to a sky with nothing in it.
	 */
	private static void writeEnd(Std140Builder builder, float seconds) {
		builder.putVec4(new Vector4f(0.0F, -1.0F, 0.0F, 0.0F))
			.putVec4(new Vector4f(0.0F, -1.0F, 0.0F, 0.0F))
			.putVec4(new Vector4f(0.0F, 0.0F, seconds, 1.0F))
			.putVec4(new Vector4f(1.0F, 0.0F, 0.0F, 0.0F))
			.putVec4(new Vector4f(0.0F, 1.0F, 0.0F, 0.0F))
			.putVec4(new Vector4f(0.0F, 0.0F, 1.0F, 0.0F));
		for (int i = 0; i < 5; i++) {
			builder.putVec4(new Vector4f(0.0F));
		}
		builder.putVec4(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F))
			.putVec4(endFlash);
	}

	/** SkyRenderer.render begins: whether Etna's sky replaces vanilla's this time. */
	public static void beginRender(SkyRenderState state) {
		boolean ready = ubo != null && noise != null;
		replacing = drawSky && ready && state.skybox == DimensionType.Skybox.OVERWORLD;
		replacingEnd = drawEnd && ready && state.skybox == DimensionType.Skybox.END;
	}

	public static void endRender() {
		replacing = false;
		replacingEnd = false;
	}

	/** Whether vanilla's sky disc, sunrise band, sun and stars stand down for Etna's sky right now. */
	public static boolean replacing() {
		return replacing;
	}

	/** Whether vanilla's moon stands down for Etna's round moon right now (Round Moon, while Etna draws the sky). */
	public static boolean drawsMoon() {
		return replacing && EtnaConfig.roundMoon;
	}

	/** Whether vanilla's End flash stands down for Etna's End sky (which draws its own) right now. */
	public static boolean replacingEnd() {
		return replacingEnd;
	}

	/** In place of vanilla's sky disc: Etna's whole sky. False leaves the disc to vanilla. */
	public static boolean drawSky(RenderPass pass, boolean withDepthAttachment) {
		if (!replacing) {
			return false;
		}
		draw(pass, withDepthAttachment, "Etna sky");
		return true;
	}

	/** In place of vanilla's End sky box: Etna's End sky. False leaves the box to vanilla. */
	public static boolean drawEndSky(RenderPass pass, boolean withDepthAttachment) {
		if (!replacingEnd) {
			return false;
		}
		draw(pass, withDepthAttachment, "Etna End sky");
		return true;
	}

	private static void draw(RenderPass pass, boolean withDepthAttachment, String label) {
		RenderPipeline p = withDepthAttachment ? pipelineWithDepth : pipeline;
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F));
		pass.pushDebugGroup(() -> label);
		pass.setPipeline(RenderSystem.getCompiledPipeline(p));
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transforms);
		pass.setUniform("EtnaSky", ubo.slice());
		// Bilinear and wrapping: every channel tiles across the texture.
		pass.setUniform("EtnaSkyNoise", noise, RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
		pass.draw(3, 1, 0, 0);
		pass.popDebugGroup();
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
	 * EtnaShadows.writeUbo: EtnaNight, bytes 544-559 of the terrain block. rgb: the colour of the aurora's
	 * light on the land (luma 1), w: how much aurora is in the sky (0 .. 1, eased; 0 by day, in rain,
	 * without a model and with the Aurora setting off).
	 */
	public static void writeNight(Std140Builder builder) {
		float amount = modelReady ? aurora : 0.0F;
		// One allocation, not one per branch: allocations merged in a conditional crashed the JVM's optimising
		// compiler (see EtnaShadows.NO_MAP).
		float on = amount > 0.0F ? 1.0F : 0.0F;
		builder.putVec4(new Vector4f(AURORA_LAND[0] * on, AURORA_LAND[1] * on, AURORA_LAND[2] * on, amount * on));
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
