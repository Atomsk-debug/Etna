package it.cosimo.etna;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The air for the world pass: how dense the mist and the light shafts' air are this frame, and what the
 * air around the camera is like. Written into the Mist, MistColor and Shafts fields of the post passes'
 * EtnaSettings block; the shaders' side is assets/etna/shaders/include/air.glsl.
 *
 * The ground mist follows the time of day: it gathers through the night, is thickest around sunrise, has
 * burnt off by late morning and comes back in the evening, and rain thickens it. It lies a little above
 * sea level and thins upwards, so it fills valleys, rivers and the sea's edge while hills rise out of it.
 * A thin haze lies over everything at night and in rain, so the distance still softens where world_light's
 * daytime atmosphere stands down. Underground (where the sky's light doesn't reach the camera) both give way
 * to a faint murk. The light shafts' air is golden-hour air: all of it with the sun low, a quarter of it
 * at noon, and thicker where the sky above the camera is covered, under trees or a roof, where gaps
 * between the leaves make shafts; in an open field there is little of it.
 * How covered the sky is and how far underground the camera is are read from the world around the camera
 * and eased over a second or two, so stepping under a tree or into a cave never switches the air at once.
 * Under Blindness and Darkness, which hide the world, everything is 0.
 *
 * Dimensions without a sun have air of their own (sunless), in the same fields (see writePost): in the
 * Nether a haze that glows in the colour of the biome's own fog, as thick as vanilla's fog there, and a low
 * mist that glows like the lava over the lava sea, thinning upwards within a few blocks; the lava sea also
 * lights the undersides of the land near it from below (the terrain's EtnaShadowFade.yz, lavaLight). The
 * haze is brighter than the dark fog it lies over, never darker, and leaves the first blocks around the
 * player clear, so the Nether reads as well as before. In the End, a faint violet haze. The Nether air
 * setting scales all of it, 0 turns it off.
 *
 * The bloom fog (bloomFog, bloomFogReach) is worked out here too: how much distant scenery feeds the bloom
 * this frame, by night, rain, how deep underground the camera is, and in the Nether and the End.
 *
 * An error here turns the mist, the shafts, the Nether's air and the bloom fog off for the session (nothing
 * added), never anything else.
 */
public final class EtnaAir {
	/** Densities per block with the setting at 1; the looks use about half of it. */
	private static final float GROUND_MIST = 0.012F;
	private static final float HAZE_DAY = 0.0004F;
	private static final float HAZE_NIGHT = 0.0035F;
	private static final float HAZE_RAIN = 0.006F;
	private static final float CAVE_MURK = 0.010F;
	/** The light shafts' air with the light rays setting at 1, the sun low and the sky above covered. */
	private static final float SHAFT_AIR = 0.012F;
	/** How much ground mist there is through the day, by the hour (0 midnight, 6 sunrise, 18 sunset). */
	private static final float[] MIST_HOURS = {0.0F, 3.0F, 5.0F, 6.0F, 7.0F, 8.0F, 9.5F, 11.0F, 12.0F, 16.0F, 17.0F, 18.0F, 19.0F, 21.0F, 24.0F};
	private static final float[] MIST_AMOUNT = {0.45F, 0.6F, 0.9F, 1.0F, 0.9F, 0.55F, 0.2F, 0.04F, 0.02F, 0.02F, 0.1F, 0.45F, 0.55F, 0.5F, 0.45F};
	/** The sky's cover is sampled on a 5x5 grid of columns this many blocks apart, centred on the camera. */
	private static final int COVER_STEP = 3;
	/** Seconds over which the cover and the depth underground ease towards their new values. */
	private static final float EASE = 1.5F;
	/** After a longer gap between frames (a menu, loading) they start where they are. */
	private static final float GAP = 1.0F;

	/**
	 * The Nether's haze with the setting at 1: optical depth across vanilla's own fog distance (its
	 * environmental fog end), so a biome vanilla fogs more thickly gets a thicker haze.
	 */
	private static final float NETHER_HAZE_DEPTH = 1.0F;
	/**
	 * The haze glows: its colour is the biome's fog colour lifted to this many times its brightness, at most
	 * to NETHER_HAZE_LUMA (display luma). A fog that is brighter already (Basalt Deltas) keeps its own.
	 */
	private static final float NETHER_HAZE_GAIN = 2.2F;
	private static final float NETHER_HAZE_LUMA = 0.3F;
	/** The lava mist's density per block at the lava sea's surface with the setting at 1. */
	private static final float LAVA_MIST = 0.05F;
	/** Its glow (display colour): the lava's own orange, a little softer. */
	private static final Vector3f LAVA_MIST_COLOR = new Vector3f(0.95F, 0.40F, 0.12F);
	/** The lava sea's surface, below sea level: its top blocks are at sea level - 1 and filled 8/9 high. */
	private static final float LAVA_SURFACE = -1.0F / 9.0F;
	/** Lava light from below on the land near the lava sea, in vanilla's units, with the setting at 1. */
	private static final float LAVA_LIGHT = 0.30F;
	/** The End's faint violet haze: density per block with the setting at 1, and its colour. */
	private static final float END_HAZE = 0.004F;
	private static final Vector3f END_HAZE_COLOR = new Vector3f(0.20F, 0.12F, 0.30F);

	/**
	 * Bloom fog: how much distant scenery feeds the bloom (bloom_prefilter.fsh), with the setting at 1, at
	 * night, in full rain, deep in a cave, in the Nether and in the End; and over how many blocks the feed
	 * builds up with distance in each.
	 */
	private static final float FOG_FEED_NIGHT = 1.0F;
	private static final float FOG_FEED_RAIN = 1.5F;
	private static final float FOG_FEED_CAVE = 1.5F;
	private static final float FOG_FEED_NETHER = 2.0F;
	private static final float FOG_FEED_END = 1.0F;
	private static final float FOG_FEED_MAX = 2.0F;
	private static final float FOG_REACH_NIGHT = 192.0F;
	private static final float FOG_REACH_RAIN = 96.0F;
	private static final float FOG_REACH_CAVE = 48.0F;
	private static final float FOG_REACH_END = 160.0F;

	/** x: ground mist, y: haze, z: cave murk (densities per block), w: camera height above sea level. */
	private static final Vector4f mist = new Vector4f();
	/** rgb: the mist's colour, w: render distance in blocks. */
	private static final Vector4f mistColor = new Vector4f();
	/** rgb: the sky model's direct light colour (luma 1), w: 1 while it has one (EtnaSky.airLight). */
	private static final Vector4f sunColor = new Vector4f();
	/** How dense the light shafts' air is, per block, while the map can be marched. */
	private static float shaftAir;
	/** How much of the sky above the camera is covered (0 .. 1), eased. */
	private static float cover;
	/** How far underground the camera is (0 open air .. 1 a cave), eased. */
	private static float cave;
	/** Bloom fog: the feed (setting times this frame's amount) and 1 / the blocks over which it builds up. */
	private static float bloomFog;
	private static float bloomFogDensity;
	/** The terrain's lava light from below (0 outside the Nether), and the lava sea's surface (world height). */
	private static float lavaLight;
	private static float lavaSurface;
	/** System.nanoTime of the last update, 0 before the first. */
	private static long lastFrame;
	private static boolean broken;

	private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	private EtnaAir() {
	}

	/**
	 * Once per frame, from EtnaShadows.prepareFrame once it has worked out the light: its direction (the moon's
	 * at night) and strength, the rain, and whether this dimension has a sun.
	 */
	public static void update(LevelRenderState state, Vector3f light, float rain, boolean hasSun) {
		long now = System.nanoTime();
		float dt = lastFrame == 0L ? GAP + 1.0F : (now - lastFrame) / 1.0e9F;
		lastFrame = now;
		if (broken) {
			return;
		}
		try {
			CameraRenderState camera = state.cameraRenderState;
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			if (level == null || camera.entityRenderState.doesMobEffectBlockSky) {
				clear();
				return;
			}
			if (!hasSun) {
				sunless(state, level, mc);
				return;
			}
			float ease = dt > GAP ? 1.0F : 1.0F - (float) Math.exp(-dt / EASE);
			cover += (coverAt(level, camera) - cover) * ease;
			// Sky light at the eye: 15 under the open sky by day and by night, falling a level per block into
			// a cave or a house. From 10 down the air turns into the cave's, all of it by 3.
			int skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(camera.blockPos);
			cave += (1.0F - smoothstep(3.0F, 10.0F, skyLight) - cave) * ease;

			// The sun's angle is 0 at noon and turns a full circle a day, a quarter of it by sunset.
			float sunAngle = state.skyRenderState.sunAngle;
			float hour = (float) (12.0 + sunAngle / (2.0 * Math.PI) * 24.0);
			hour = ((hour % 24.0F) + 24.0F) % 24.0F;
			// The sun's height on its tilted path (EtnaSky), as the sky and the shadows have it.
			float sunHeight = EtnaShadows.sunDirection().y;
			float night = 1.0F - smoothstep(-0.25F, 0.05F, sunHeight);
			float setting = EtnaConfig.mist.value;
			float open = 1.0F - cave;
			mist.set(
				GROUND_MIST * mistAmount(hour) * (1.0F + 0.6F * rain) * setting * open,
				(HAZE_DAY + HAZE_NIGHT * night + HAZE_RAIN * rain) * setting * open,
				CAVE_MURK * cave * setting,
				(float) (camera.pos.y - level.getSeaLevel())
			);
			// The mist's colour: while Etna draws the sky, its horizon averaged all round (EtnaSky), so the mist
			// matches the sky behind it and keeps its colour as the camera turns. Otherwise vanilla's fog colour
			// (navy at night, grey in rain, towards the sunset's colour looking at a low sun).
			if (!EtnaSky.mistColor(mistColor)) {
				mistColor.set(camera.fogData.color.x, camera.fogData.color.y, camera.fogData.color.z, 0.0F);
			}
			mistColor.w = mc.options.getEffectiveRenderDistance() * 16.0F;
			EtnaSky.airLight(sunColor);
			// Golden hour: all of the shafts' air with the light low, a quarter of it with the light high.
			float gold = 0.25F + 0.75F * (1.0F - smoothstep(0.1F, 0.55F, light.y));
			shaftAir = SHAFT_AIR * EtnaConfig.lightRays.value * gold * (0.15F + 0.85F * cover);
			lavaLight = 0.0F;
			// Bloom fog: night, rain and caves each add their share of feed, and the distance it builds up over
			// is their mix, weighted by those shares.
			float nightFeed = FOG_FEED_NIGHT * night * open;
			float rainFeed = FOG_FEED_RAIN * rain * open;
			float caveFeed = FOG_FEED_CAVE * cave;
			float feed = nightFeed + rainFeed + caveFeed;
			bloomFog = EtnaConfig.bloomFog.value * Math.min(feed, FOG_FEED_MAX);
			bloomFogDensity = feed > 1e-4F
				? (nightFeed / FOG_REACH_NIGHT + rainFeed / FOG_REACH_RAIN + caveFeed / FOG_REACH_CAVE) / feed
				: 1.0F / FOG_REACH_NIGHT;
		} catch (RuntimeException e) {
			broken = true;
			clear();
			EtnaClient.LOGGER.error("Etna: the mist, the light shafts and the Nether's air failed, they are off for this session", e);
		}
	}

	/** Nothing in the air: no mist, no shafts, open sky, no Nether air, no bloom fog. */
	private static void clear() {
		mist.set(0.0F);
		mistColor.set(0.0F);
		sunColor.set(0.0F);
		shaftAir = 0.0F;
		cover = 0.0F;
		cave = 0.0F;
		bloomFog = 0.0F;
		bloomFogDensity = 0.0F;
		lavaLight = 0.0F;
		lavaSurface = 0.0F;
	}

	/**
	 * The air of a dimension without a sun, in the fields writePost hands the world pass (air.glsl's
	 * etnaSunlessAir): Mist x the lava mist's density at the lava sea's surface, y the haze's density, w the
	 * camera's height above the lava sea's surface; MistColor rgb the haze's glow, w the distance vanilla's
	 * fog ends at, where the haze stops thickening; AirSun rgb the lava mist's glow. The End (its own sky)
	 * gets the violet haze alone; any other sunless dimension the Nether's air, with all of the lava mist
	 * where the biome's fog is warm and less of it where it is not.
	 */
	private static void sunless(LevelRenderState state, ClientLevel level, Minecraft mc) {
		CameraRenderState camera = state.cameraRenderState;
		FogData fog = camera.fogData;
		boolean end = state.skyRenderState.skybox == DimensionType.Skybox.END;
		shaftAir = 0.0F;
		cover = 0.0F;
		cave = 0.0F;
		float setting = EtnaConfig.netherAir.value;
		float renderDistance = mc.options.getEffectiveRenderDistance() * 16.0F;
		float fogEnd = Math.clamp(fog.environmentalEnd, 16.0F, renderDistance);
		// A boss's world fog (the Wither) is meant to hide things: vanilla's then, as for Blindness.
		if (setting <= 0.0F || fog.shouldCreateBossFog) {
			mist.set(0.0F);
			mistColor.set(0.0F);
			sunColor.set(0.0F);
			lavaLight = 0.0F;
			lavaSurface = 0.0F;
		} else if (end) {
			mist.set(0.0F, END_HAZE * setting, 0.0F, 0.0F);
			mistColor.set(END_HAZE_COLOR.x, END_HAZE_COLOR.y, END_HAZE_COLOR.z, renderDistance);
			sunColor.set(0.0F);
			lavaLight = 0.0F;
			lavaSurface = 0.0F;
		} else {
			float r = fog.color.x;
			float g = fog.color.y;
			float b = fog.color.z;
			float luma = 0.2126F * r + 0.7152F * g + 0.0722F * b;
			float glowLuma = Math.max(luma, Math.min(luma * NETHER_HAZE_GAIN, NETHER_HAZE_LUMA));
			float lift = luma > 1e-4F ? glowLuma / luma : 0.0F;
			// Warm fog (the Nether Wastes, the Crimson Forest): all of the lava mist. Cool or grey fog (the Warped
			// Forest, the Soul Sand Valley, the Basalt Deltas): less of it, so their own colour leads.
			float warm = smoothstep(0.0F, 0.5F, (r - Math.max(g, b)) / Math.max(r, 1e-3F));
			lavaSurface = level.getSeaLevel() + LAVA_SURFACE;
			mist.set(
				LAVA_MIST * setting * (0.4F + 0.6F * warm),
				NETHER_HAZE_DEPTH / fogEnd * setting,
				0.0F,
				(float) (camera.pos.y - lavaSurface)
			);
			mistColor.set(r * lift, g * lift, b * lift, fogEnd);
			sunColor.set(LAVA_MIST_COLOR.x, LAVA_MIST_COLOR.y, LAVA_MIST_COLOR.z, 0.0F);
			lavaLight = LAVA_LIGHT * setting;
		}
		bloomFog = EtnaConfig.bloomFog.value * (end ? FOG_FEED_END : FOG_FEED_NETHER);
		bloomFogDensity = end ? 1.0F / FOG_REACH_END : 1.0F / Math.max(0.5F * fogEnd, 16.0F);
	}

	/** The share of a 5x5 grid of columns around the camera whose highest solid block or leaf is above the eye. */
	private static float coverAt(ClientLevel level, CameraRenderState camera) {
		BlockPos eye = camera.blockPos;
		double eyeY = camera.pos.y + 0.5;
		int covered = 0;
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				// The first free block above the column's top, so a top above the eye means a roof over it.
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, eye.getX() + dx * COVER_STEP, eye.getZ() + dz * COVER_STEP);
				if (top > eyeY) {
					covered++;
				}
			}
		}
		return covered / 25.0F;
	}

	/** The ground mist's share by the hour, eased between the table's points. */
	private static float mistAmount(float hour) {
		for (int i = 1; i < MIST_HOURS.length; i++) {
			if (hour <= MIST_HOURS[i]) {
				float t = smoothstep(MIST_HOURS[i - 1], MIST_HOURS[i], hour);
				return MIST_AMOUNT[i - 1] + (MIST_AMOUNT[i] - MIST_AMOUNT[i - 1]) * t;
			}
		}
		return MIST_AMOUNT[0];
	}

	/**
	 * The Mist, MistColor, Shafts and AirSun vec4s of the post passes' EtnaSettings block, bytes 352 to 415. Shafts.w is
	 * the shadow map's radius while the shafts pass can march the map this frame, else 0: the shaders then
	 * leave the shafts out and light the mist by the open sky instead. Without a sun (EtnaSettings' Air.w 0)
	 * Mist, MistColor and AirSun hold the sunless air instead (see sunless).
	 */
	public static void writePost(Std140Builder builder) {
		boolean anything = shaftAir > 0.0F || mist.x > 0.0F || mist.y > 0.0F || mist.z > 0.0F;
		float radius = anything ? EtnaShadows.shaftRadius() : 0.0F;
		builder.putVec4(mist)
			.putVec4(mistColor)
			.putVec4(new Vector4f(radius > 0.0F ? shaftAir : 0.0F, cover, cave, radius))
			.putVec4(sunColor);
	}

	/** The bloom fog's feed this frame, for EtnaSettings' Glow.y: the setting times the amount for the state of the air. */
	public static float bloomFog() {
		return bloomFog;
	}

	/** One over the distance in blocks over which the bloom fog builds up, for EtnaSettings' Glow.w. */
	public static float bloomFogReach() {
		return bloomFogDensity;
	}

	/** The terrain's lava light from below, for its EtnaShadowFade.y: 0 outside the Nether. */
	public static float lavaLight() {
		return lavaLight;
	}

	/** The lava sea's surface (world height), for the terrain's EtnaShadowFade.z. */
	public static float lavaSurface() {
		return lavaSurface;
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
