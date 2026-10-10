package it.cosimo.etna;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;

/** Tiny properties file in config/etna.properties. */
public final class EtnaConfig {
	/** Bumped when the meaning of the look settings changes: older files start from the new defaults. */
	private static final int VERSION = 3;

	/** How much work Etna does. Changing any of these settings by hand switches to CUSTOM. */
	public enum Quality { LOW, MEDIUM, HIGH, ULTRA, CUSTOM }

	/** Ready-made sets of every look setting. Moving any of them by hand switches to CUSTOM. */
	public enum Profile { NATURAL, SOFT, VIVID, CINEMATIC, CUSTOM }

	/** The performance log in latest.log: none, or one summary line every few seconds. */
	public enum Profiling { OFF, SUMMARY }

	// 0.8.0 MOTES
	/** How many fireflies and motes float around at most (EtnaMotes): none, a few, a fair number, many. */
	public enum Fireflies { OFF, FEW, NORMAL, MANY }

	/** Fireflies (Effects page): not part of the looks, it stays where it is when the look changes. */
	public static Fireflies fireflies = Fireflies.NORMAL;

	/** One adjustable number: its key in the file and menu, its range and the value it has now. */
	public static final class Setting {
		public final String key;
		public final float min;
		public final float max;
		public float value;

		Setting(String key, float min, float max) {
			this.key = key;
			this.min = min;
			this.max = max;
		}

		public void set(float v) {
			this.value = Math.clamp(v, this.min, this.max);
		}
	}

	// Colour
	public static final Setting brightness = new Setting("brightness", 0.5F, 1.5F);
	public static final Setting contrast = new Setting("contrast", 0.8F, 1.5F);
	public static final Setting saturation = new Setting("saturation", 0.0F, 2.0F);
	public static final Setting vibrance = new Setting("vibrance", 0.0F, 1.0F);
	public static final Setting warmth = new Setting("warmth", -1.0F, 1.0F);
	public static final Setting vignette = new Setting("vignette", 0.0F, 1.0F);
	public static final Setting sharpen = new Setting("sharpen", 0.0F, 1.0F);
	/**
	 * How far exposure and night vision follow the light around the camera (EtnaEye); 0 turns both off, and
	 * Visibility's wider eyes in the dark with them.
	 */
	public static final Setting eyeAdaptation = new Setting("eyeAdaptation", 0.0F, 1.0F);
	// Effects
	public static final Setting bloom = new Setting("bloom", 0.0F, 3.0F);
	public static final Setting bloomSize = new Setting("bloomSize", 0.25F, 2.0F);
	public static final Setting ambientOcclusion = new Setting("ambientOcclusion", 0.0F, 1.0F);
	public static final Setting haze = new Setting("haze", 0.0F, 1.0F);
	public static final Setting waterReflections = new Setting("waterReflections", 0.0F, 1.0F);
	// Lighting
	public static final Setting sunlight = new Setting("sunlight", 0.5F, 2.0F);
	public static final Setting shadowDarkness = new Setting("shadowDarkness", 0.0F, 1.0F);
	public static final Setting shadowSoftness = new Setting("shadowSoftness", 0.25F, 4.0F);
	public static final Setting lightRays = new Setting("lightRays", 0.0F, 3.0F);
	// Surfaces
	public static final Setting surfaceDetail = new Setting("surfaceDetail", 0.0F, 1.0F);
	public static final Setting shine = new Setting("shine", 0.0F, 1.0F);
	public static final Setting leafGlow = new Setting("leafGlow", 0.0F, 1.0F);
	public static final Setting torchWarmth = new Setting("torchWarmth", 0.0F, 1.0F);
	// Sky and air
	public static final Setting skyGlow = new Setting("skyGlow", 0.0F, 1.0F);
	public static final Setting atmosphere = new Setting("atmosphere", 0.0F, 1.0F);
	/** How much of the sun a cloud overhead takes away from the land (EtnaClouds): 0 casts no cloud shadows. */
	public static final Setting cloudShadows = new Setting("cloudShadows", 0.0F, 1.0F);
	/** Ground and valley mist at dawn and dusk, haze at night and in rain, murk in caves (EtnaAir). */
	public static final Setting mist = new Setting("mist", 0.0F, 1.0F);
	// Dark places
	public static final Setting caveLight = new Setting("caveLight", 0.0F, 1.0F);
	public static final Setting emission = new Setting("emission", 0.0F, 1.0F);
	// Water (underwater visibility in blocks)
	public static final Setting underwaterVisibility = new Setting("underwaterVisibility", 48.0F, 256.0F);
	public static final Setting waterClarity = new Setting("waterClarity", 0.0F, 1.0F);
	public static final Setting caustics = new Setting("caustics", 0.0F, 1.0F);
	public static final Setting underwaterRays = new Setting("underwaterRays", 0.0F, 2.0F);
	/**
	 * Visibility (General page): how much better dark places read, without making bright ones brighter. It
	 * works on several things at once: more of the cave light (caveLightFloor), lighter sun and moon shadows
	 * (shadowDarknessShown), eyes that open up further in the dark (EtnaEye) and a lift of the darkest tones
	 * in the final pass (final.fsh). 0 is 0.7.0's picture. It is not part of the looks: it stays where it is
	 * when the look changes, and moving it keeps the look.
	 */
	public static final Setting visibility = new Setting("visibility", 0.0F, 1.0F);
	public static final float VISIBILITY_DEFAULT = 0.3F;
	public static final float SUN_PATH_TILT_DEFAULT = 25.0F;

	// 0.8.0 MOOD
	/**
	 * Colour moods laid over the Look (EtnaMood), each matched to a family of resource packs or purely magic.
	 * Like Visibility they are not part of the Look: changing the Look keeps the mood, and the other way round.
	 */
	public enum Mood { NATURAL, CLEAR_DAY, TRAILER, STORYBOOK, OLD_KINGDOM, INK, LIFELIKE, ENCHANTED, MYSTIC, AUTUMN, PASTEL }

	public static Mood mood = Mood.NATURAL;
	/** How far the mood is applied: 0 is the Look alone (no dreamy glow either), 1 the full mood. */
	public static final Setting moodStrength = new Setting("moodStrength", 0.0F, 1.0F);
	public static final float MOOD_STRENGTH_DEFAULT = 1.0F;

	static {
		moodStrength.set(MOOD_STRENGTH_DEFAULT);
	}

	// 0.8.0 WEATHER
	/** Rain and snow: vanilla's, or Etna's thinner rain in Etna's light and softer snow (EtnaWeather). */
	public enum RainLook { VANILLA, ETNA }

	public static RainLook rainLook = RainLook.ETNA;
	/**
	 * Bloom fog (EFFECTS page): how much distant scenery feeds the bloom at night, in rain, in caves and in the
	 * Nether, so distant lights glow in the damp air (bloom_prefilter.fsh, EtnaAir.bloomFog). 0 is off.
	 */
	public static final Setting bloomFog = new Setting("bloomFog", 0.0F, 1.0F);
	public static final float BLOOM_FOG_DEFAULT = 0.4F;
	/** Nether air (SKY page): the Nether's glowing haze and lava mist, and the End's faint violet air (EtnaAir). 0 is off. */
	public static final Setting netherAir = new Setting("netherAir", 0.0F, 1.0F);
	public static final float NETHER_AIR_DEFAULT = 0.6F;

	static {
		bloomFog.set(BLOOM_FOG_DEFAULT);
		netherAir.set(NETHER_AIR_DEFAULT);
	}

	/** Back to the defaults (the settings screen's Reset All); like visibility, these are not part of the looks. */
	public static void resetWeather() {
		rainLook = RainLook.ETNA;
		bloomFog.set(BLOOM_FOG_DEFAULT);
		netherAir.set(NETHER_AIR_DEFAULT);
	}

	// 0.8.0 SURFACES: the Surfaces page's materials, puddles, sparkles and glowing flowers (EtnaMaterials,
	// terrain_surface.glsl). Like Visibility they are not part of the looks: they stay where they are when the
	// look changes, and moving them keeps the look.
	/** How much metals, gems, polished stone, glass and ice shine by type, and ore specks with them; 0 is 0.7.1's plain sheen. */
	public static final Setting materials = new Setting("materials", 0.0F, 1.0F);
	/** How much of the open ground puddles cover while it is wet (they need Shine, which makes ground wet at all). */
	public static final Setting puddles = new Setting("puddles", 0.0F, 1.0F);
	/** Sun and moon glints on snow, sand, ice, gems and ore specks, and torch glints on gems and ores. */
	public static final Setting sparkles = new Setting("sparkles", 0.0F, 1.0F);
	/** How brightly the petals and berries of plants glow at night. */
	public static final Setting glowingFlowers = new Setting("glowingFlowers", 0.0F, 1.0F);
	private static final float MATERIALS_DEFAULT = 0.7F;
	private static final float PUDDLES_DEFAULT = 0.7F;
	private static final float SPARKLES_DEFAULT = 0.6F;
	private static final float GLOWING_FLOWERS_DEFAULT = 0.5F;

	/** The Surfaces page's materials, puddles, sparkles and glowing flowers back to their defaults. */
	public static void resetSurfaces() {
		materials.set(MATERIALS_DEFAULT);
		puddles.set(PUDDLES_DEFAULT);
		sparkles.set(SPARKLES_DEFAULT);
		glowingFlowers.set(GLOWING_FLOWERS_DEFAULT);
	}

	// 0.8.0 WATER
	/**
	 * Shoreline foam (Water page): a thin line of foam where water meets the shore (terrain_water.glsl). Like
	 * Visibility it is not part of the looks: it stays where it is when the look changes.
	 */
	public static final Setting shoreFoam = new Setting("shoreFoam", 0.0F, 1.0F);
	public static final float SHORE_FOAM_DEFAULT = 0.5F;
	/** Glitter (Water page): sparkles of the sun and the moon on the waves. Not part of the looks either. */
	public static final Setting waterGlitter = new Setting("waterGlitter", 0.0F, 1.0F);
	public static final float WATER_GLITTER_DEFAULT = 0.5F;

	static {
		shoreFoam.set(SHORE_FOAM_DEFAULT);
		waterGlitter.set(WATER_GLITTER_DEFAULT);
	}

	/** Every look setting, in the order profiles list their values. */
	public static final List<Setting> LOOK = List.of(
		brightness, contrast, saturation, vibrance, warmth, vignette, sharpen,
		bloom, bloomSize, ambientOcclusion, haze, waterReflections,
		sunlight, shadowDarkness, shadowSoftness, lightRays,
		surfaceDetail, shine, leafGlow, torchWarmth, skyGlow, atmosphere,
		caveLight, emission, underwaterVisibility, waterClarity, caustics, underwaterRays, eyeAdaptation, cloudShadows, mist
	);

	public static final int[] SHADOW_RESOLUTIONS = {512, 1024, 1536, 2048, 3072, 4096, 6144, 8192};

	public static boolean enabled = true;
	public static Quality quality = Quality.HIGH;
	public static Profile profile = Profile.NATURAL;
	public static int toggleKey = InputConstants.KEY_F7;
	public static boolean shadows = true;
	public static int shadowResolution = 2048;
	public static float shadowDistance = 96.0F;
	/** Temporal anti-aliasing; ambient occlusion runs in the same pass and needs it. Without it, the plain world pass runs while shadows are on. */
	public static boolean antiAliasing = true;
	/**
	 * How far the sun's path across the sky leans to the south, in degrees (0 is vanilla's path straight
	 * overhead). The sun, the moon, the stars and the shadows all follow it (EtnaSky.tilt).
	 */
	public static float sunPathTilt = SUN_PATH_TILT_DEFAULT;
	/** macOS: open the window without Retina pixel density, so the game draws a quarter of the pixels. */
	public static boolean halfResolution = false;
	/** The performance log: a line in latest.log every few seconds while in a world. */
	public static Profiling profiling = Profiling.OFF;
	/** Skip shadow casters that can't shadow anything on screen. Only in the file, to compare with and without. */
	public static boolean shadowCulling = true;
	/**
	 * Read the shadow map four texels at a time (textureGather). Only in the file, read at start: false reads one
	 * texel at a time as before 0.6.0 (same picture, slower), in case a graphics driver gets the gather wrong.
	 */
	public static boolean shadowGather = true;

	// 0.8.0 SKY
	/** The night sky's stars (EtnaSky): none, stars, stars with the Milky Way, or the Milky Way with nebula colours. */
	public enum NightSky { OFF, STARS, MILKY_WAY, NEBULA }

	/** When the aurora shows on clear nights: never, over cold biomes, also under a full moon, or every night. */
	public enum Aurora { OFF, COLD, COLD_FULL_MOON, EVERY_NIGHT }

	/** How often a shooting star crosses the night sky. */
	public enum ShootingStars { OFF, RARE, FREQUENT }

	/** When a rainbow can stand opposite a low sun: never, just after rain, or on any clear morning and evening. */
	public enum Rainbows { OFF, AFTER_RAIN, ALWAYS }

	public static NightSky nightSky = NightSky.MILKY_WAY;
	public static Aurora aurora = Aurora.COLD_FULL_MOON;
	public static ShootingStars shootingStars = ShootingStars.RARE;
	public static Rainbows rainbows = Rainbows.AFTER_RAIN;
	/** Etna draws a round moon with its phase (and a halo on cold clear nights) in place of vanilla's square one. */
	public static boolean roundMoon = true;
	/** Etna draws the End's sky (a violet nebula, small dense stars and the flash) in place of vanilla's box. */
	public static boolean endSky = true;
	/**
	 * How far the sky's air takes the biome's own sky colour (vanilla's: more turquoise over deserts, more lavender over snow): 0 is
	 * Etna's one sky everywhere. Like visibility, it is not part of the looks.
	 */
	public static final Setting biomeSkyColor = new Setting("biomeSkyColor", 0.0F, 1.0F);
	public static final float BIOME_SKY_COLOR_DEFAULT = 0.5F;

	static {
		resetSky();
	}

	/** The SKY settings back to their defaults (the settings screen's Reset All). */
	public static void resetSky() {
		nightSky = NightSky.MILKY_WAY;
		aurora = Aurora.COLD_FULL_MOON;
		shootingStars = ShootingStars.RARE;
		rainbows = Rainbows.AFTER_RAIN;
		roundMoon = true;
		endSky = true;
		biomeSkyColor.set(BIOME_SKY_COLOR_DEFAULT);
	}
	// end 0.8.0 SKY

	private static boolean loaded;

	static {
		applyProfile(Profile.NATURAL);
		applyQuality(Quality.HIGH);
		visibility.set(VISIBILITY_DEFAULT);
		resetSurfaces();
	}

	/**
	 * The cave light's floor (EtnaDark.x), in lightmap units: a quarter of the square root of the cave light
	 * setting, which Visibility raises by up to twice the setting (at most to full) and 30 % on top (Natural's
	 * 0.4 at full Visibility lights caves about 84 % more). Cave light Off stays off.
	 */
	public static float caveLightFloor() {
		float v = visibility.value;
		float cave = Math.min(Math.max(caveLight.value, 0.0F) * (1.0F + v), 1.0F);
		return 0.25F * (float) Math.sqrt(cave) * (1.0F + 0.3F * v);
	}

	/** How dark sun and moon shadows are drawn: the setting, a third lighter at full Visibility. */
	public static float shadowDarknessShown() {
		return shadowDarkness.value * (1.0F - 0.35F * visibility.value);
	}

	/** Values in LOOK order. */
	private static float[] profileValues(Profile p) {
		return switch (p) {
			// The grade sits near Complementary Reimagined's calm one (contrast about 1.05, colour about +7 %); Vivid
			// is a little punchier and Cinematic moodier. The vignettes leave bright corners alone, so they can be
			// a little stronger than before 0.7.0 and still read lighter.
			//                         bright contr sat   vib   warm  vign  sharp bloom bSize ao    haze  water sun   shDark shSoft rays  detail shine leaf  torch skyGl atmos cave  glow  uwVis   clar  caust uwRays eye clouds mist
			case NATURAL -> new float[] {1.0F, 1.05F, 1.05F, 0.06F, 0.1F, 0.25F, 0.45F, 1.0F, 1.0F, 0.6F, 0.35F, 0.8F, 1.0F, 0.55F, 1.5F, 0.6F, 0.5F, 0.5F, 0.6F, 0.6F, 0.6F, 0.5F, 0.4F, 0.5F, 128.0F, 0.6F, 0.6F, 1.0F, 1.0F, 0.55F, 0.5F};
			case SOFT -> new float[] {1.02F, 1.0F, 0.98F, 0.08F, 0.05F, 0.15F, 0.3F, 1.2F, 1.3F, 0.45F, 0.45F, 0.7F, 0.95F, 0.45F, 2.2F, 0.5F, 0.35F, 0.4F, 0.5F, 0.5F, 0.6F, 0.6F, 0.5F, 0.45F, 144.0F, 0.55F, 0.45F, 0.8F, 1.0F, 0.4F, 0.6F};
			case VIVID -> new float[] {1.02F, 1.08F, 1.1F, 0.15F, 0.12F, 0.3F, 0.55F, 1.0F, 1.0F, 0.7F, 0.3F, 0.85F, 1.08F, 0.65F, 1.3F, 0.7F, 0.65F, 0.6F, 0.7F, 0.7F, 0.7F, 0.45F, 0.5F, 0.7F, 160.0F, 0.7F, 0.75F, 1.2F, 1.0F, 0.65F, 0.45F};
			case CINEMATIC -> new float[] {0.97F, 1.12F, 1.0F, 0.15F, 0.25F, 0.45F, 0.45F, 1.5F, 1.5F, 0.7F, 0.5F, 0.85F, 1.05F, 0.7F, 1.8F, 1.2F, 0.6F, 0.6F, 0.8F, 0.8F, 0.8F, 0.65F, 0.3F, 0.6F, 112.0F, 0.5F, 0.7F, 1.5F, 0.6F, 0.7F, 0.65F};
			case CUSTOM -> null;
		};
	}

	public static void applyProfile(Profile p) {
		profile = p;
		float[] values = profileValues(p);
		if (values == null) {
			return;
		}
		for (int i = 0; i < LOOK.size(); i++) {
			LOOK.get(i).set(values[i]);
		}
	}

	public static void applyQuality(Quality q) {
		quality = q;
		switch (q) {
			case LOW -> setQuality(false, 1024, 64.0F, false);
			case MEDIUM -> setQuality(true, 1024, 64.0F, true);
			case HIGH -> setQuality(true, 2048, 96.0F, true);
			// 3072 rather than 4096: the distortion keeps shadows sharp near you, for 44% fewer texels to draw.
			case ULTRA -> setQuality(true, 3072, 160.0F, true);
			case CUSTOM -> {
			}
		}
	}

	private static void setQuality(boolean shadowsOn, int resolution, float distance, boolean temporal) {
		shadows = shadowsOn;
		shadowResolution = resolution;
		shadowDistance = distance;
		antiAliasing = temporal;
	}

	public static boolean shadowsActive() {
		return enabled && shadows;
	}

	/** Whether the world pass (anti-aliasing and ambient occlusion) runs. */
	public static boolean temporalActive() {
		return enabled && antiAliasing;
	}

	/**
	 * Whether a world pass runs at all, so sky glow, atmosphere, mist and light shafts can show: the full one with
	 * anti-aliasing, or the plain one (EtnaFrame) with shadows on and anti-aliasing off. Low quality turns both off
	 * and keeps 0.6.0's speed.
	 */
	public static boolean worldPassActive() {
		return enabled && (antiAliasing || shadows);
	}

	/**
	 * Ambient occlusion is computed in the world pass with anti-aliasing, which averages out its noise: without
	 * it there is none. EtnaFrame.temporal is false too once the anti-aliased world chain has failed and the
	 * plain one (no anti-aliasing) stands in for it, where the noise would flicker.
	 */
	public static float ambientOcclusionStrength() {
		return temporalActive() && EtnaFrame.temporal() ? ambientOcclusion.value : 0.0F;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("etna.properties");
	}

	public static void load() {
		loaded = true;
		Path path = file();
		if (!Files.exists(path)) {
			save();
			return;
		}
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(path)) {
			props.load(reader);
		} catch (IOException | IllegalArgumentException e) {
			EtnaClient.LOGGER.warn("Etna: could not read {}, using defaults", path, e);
			return;
		}
		enabled = bool(props, "enabled", true);
		toggleKey = (int) number(props, "toggleKey", InputConstants.KEY_F7, 0, Integer.MAX_VALUE);
		if ((int) number(props, "version", 0, 0, Integer.MAX_VALUE) < VERSION) {
			// Files from before 0.3.0: the look settings meant something else and half resolution blurred
			// the picture. Start from the new defaults and write them back.
			save();
			return;
		}
		halfResolution = bool(props, "halfResolution", false);
		sunPathTilt = number(props, "sunPathTilt", SUN_PATH_TILT_DEFAULT, 0.0F, 45.0F);
		visibility.set(number(props, "visibility", VISIBILITY_DEFAULT, visibility.min, visibility.max));
		// 0.8.0 MOOD
		mood = choice(props, "mood", Mood.NATURAL);
		moodStrength.set(number(props, moodStrength.key, MOOD_STRENGTH_DEFAULT, moodStrength.min, moodStrength.max));
		// 0.8.0 WEATHER
		rainLook = choice(props, "rainLook", RainLook.ETNA);
		bloomFog.set(number(props, bloomFog.key, BLOOM_FOG_DEFAULT, bloomFog.min, bloomFog.max));
		netherAir.set(number(props, netherAir.key, NETHER_AIR_DEFAULT, netherAir.min, netherAir.max));
		// 0.8.0 SURFACES
		materials.set(number(props, materials.key, MATERIALS_DEFAULT, materials.min, materials.max));
		puddles.set(number(props, puddles.key, PUDDLES_DEFAULT, puddles.min, puddles.max));
		sparkles.set(number(props, sparkles.key, SPARKLES_DEFAULT, sparkles.min, sparkles.max));
		glowingFlowers.set(number(props, glowingFlowers.key, GLOWING_FLOWERS_DEFAULT, glowingFlowers.min, glowingFlowers.max));
		// 0.8.0 WATER
		shoreFoam.set(number(props, shoreFoam.key, SHORE_FOAM_DEFAULT, shoreFoam.min, shoreFoam.max));
		waterGlitter.set(number(props, waterGlitter.key, WATER_GLITTER_DEFAULT, waterGlitter.min, waterGlitter.max));
		profiling = choice(props, "profiling", Profiling.OFF);
		shadowCulling = bool(props, "shadowCulling", true);
		shadowGather = bool(props, "shadowGather", true);
		// 0.8.0 MOTES
		fireflies = choice(props, "fireflies", Fireflies.NORMAL);
		applyQuality(choice(props, "quality", Quality.HIGH));
		if (quality == Quality.CUSTOM) {
			shadows = bool(props, "shadows", true);
			shadowResolution = (int) number(props, "shadowResolution", 2048, 512, 8192);
			shadowDistance = number(props, "shadowDistance", 96.0F, 16.0F, 256.0F);
			antiAliasing = bool(props, "antiAliasing", true);
		}
		applyProfile(choice(props, "profile", Profile.NATURAL));
		if (profile == Profile.CUSTOM) {
			for (Setting setting : LOOK) {
				setting.set(number(props, setting.key, setting.value, setting.min, setting.max));
			}
		}
		// 0.8.0 SKY
		nightSky = choice(props, "nightSky", NightSky.MILKY_WAY);
		aurora = choice(props, "aurora", Aurora.COLD_FULL_MOON);
		shootingStars = choice(props, "shootingStars", ShootingStars.RARE);
		rainbows = choice(props, "rainbows", Rainbows.AFTER_RAIN);
		roundMoon = bool(props, "roundMoon", true);
		endSky = bool(props, "endSky", true);
		biomeSkyColor.set(number(props, "biomeSkyColor", BIOME_SKY_COLOR_DEFAULT, biomeSkyColor.min, biomeSkyColor.max));
	}

	/** For code that can run before the client entrypoint (the window is created very early). */
	public static void ensureLoaded() {
		if (!loaded) {
			load();
		}
	}

	public static void save() {
		Properties props = new Properties();
		props.setProperty("version", Integer.toString(VERSION));
		props.setProperty("enabled", Boolean.toString(enabled));
		props.setProperty("toggleKey", Integer.toString(toggleKey));
		props.setProperty("halfResolution", Boolean.toString(halfResolution));
		props.setProperty("sunPathTilt", Float.toString(sunPathTilt));
		props.setProperty("visibility", Float.toString(visibility.value));
		// 0.8.0 MOOD
		props.setProperty("mood", mood.name().toLowerCase(Locale.ROOT));
		props.setProperty(moodStrength.key, Float.toString(moodStrength.value));
		// 0.8.0 WEATHER
		props.setProperty("rainLook", rainLook.name().toLowerCase(Locale.ROOT));
		props.setProperty(bloomFog.key, Float.toString(bloomFog.value));
		props.setProperty(netherAir.key, Float.toString(netherAir.value));
		// 0.8.0 SURFACES
		for (Setting setting : List.of(materials, puddles, sparkles, glowingFlowers)) {
			props.setProperty(setting.key, Float.toString(setting.value));
		}
		// 0.8.0 WATER
		props.setProperty(shoreFoam.key, Float.toString(shoreFoam.value));
		props.setProperty(waterGlitter.key, Float.toString(waterGlitter.value));
		props.setProperty("profiling", profiling.name().toLowerCase(Locale.ROOT));
		props.setProperty("shadowCulling", Boolean.toString(shadowCulling));
		props.setProperty("shadowGather", Boolean.toString(shadowGather));
		// 0.8.0 MOTES
		props.setProperty("fireflies", fireflies.name().toLowerCase(Locale.ROOT));
		props.setProperty("quality", quality.name().toLowerCase(Locale.ROOT));
		props.setProperty("shadows", Boolean.toString(shadows));
		props.setProperty("shadowResolution", Integer.toString(shadowResolution));
		props.setProperty("shadowDistance", Float.toString(shadowDistance));
		props.setProperty("antiAliasing", Boolean.toString(antiAliasing));
		props.setProperty("profile", profile.name().toLowerCase(Locale.ROOT));
		for (Setting setting : LOOK) {
			props.setProperty(setting.key, Float.toString(setting.value));
		}
		// 0.8.0 SKY: nightSky off | stars | milky_way | nebula, aurora off | cold | cold_full_moon | every_night,
		// shootingStars off | rare | frequent, rainbows off | after_rain | always, biomeSkyColor 0 .. 1.
		props.setProperty("nightSky", nightSky.name().toLowerCase(Locale.ROOT));
		props.setProperty("aurora", aurora.name().toLowerCase(Locale.ROOT));
		props.setProperty("shootingStars", shootingStars.name().toLowerCase(Locale.ROOT));
		props.setProperty("rainbows", rainbows.name().toLowerCase(Locale.ROOT));
		props.setProperty("roundMoon", Boolean.toString(roundMoon));
		props.setProperty("endSky", Boolean.toString(endSky));
		props.setProperty("biomeSkyColor", Float.toString(biomeSkyColor.value));
		try (Writer writer = Files.newBufferedWriter(file())) {
			props.store(writer, "Etna shaders. quality: low | medium | high | ultra | custom (shadows, shadowResolution,"
				+ " shadowDistance and antiAliasing are only read back with quality=custom). profile: natural | soft | vivid"
				+ " | cinematic | custom (the look values are only read back with profile=custom; eyeAdaptation: 0 (off) .. 1)."
				+ " toggleKey: key code, F7 by"
				+ " default. halfResolution: macOS only, applies after a restart. sunPathTilt: 0 to 45, how many degrees the"
				+ " path of the sun and the moon leans to the south (0 is vanilla's path). visibility: 0 .. 1, how much better caves,"
				+ " nights and shade read (0: as in 0.7.0), kept whatever the profile. profiling: off | summary (a performance line in"
				+ " latest.log every few seconds). shadowCulling: true | false (false draws every shadow caster, to compare)."
				+ " shadowGather: true | false (false reads the shadow map one texel at a time, as before 0.6.0; applies after"
				+ " a restart). cloudShadows: 0 .. 1, how much a cloud overhead dims the sun on the land (0: no cloud shadows)."
				+ " mist: 0 .. 1, ground and valley mist, night and rain haze, cave murk (0: off). mood: natural | clear_day | trailer"
				+ " | storybook | old_kingdom | ink | lifelike | enchanted | mystic | autumn | pastel, kept whatever the profile."
				+ " moodStrength: 0 .. 1 (0: the look alone). fireflies: off | few | normal"
				+ " | many, how many fireflies and motes float around at most, kept whatever the profile."
				+ " rainLook: vanilla | etna. bloomFog: 0 .. 1, distant glow at night, in rain, in caves and in the Nether (0: off)."
				+ " netherAir: 0 .. 1, the Nether's haze and lava mist and the End's violet air (0: off); all three kept whatever the profile."
				+ " materials, puddles, sparkles, glowingFlowers: 0 .. 1 (0: off), kept whatever the profile."
				+ " shoreFoam, waterGlitter: 0 .. 1, foam along the shore and sparkles on the waves (0: off), kept whatever the profile");
		} catch (IOException e) {
			EtnaClient.LOGGER.warn("Etna: could not save config", e);
		}
	}

	private static boolean bool(Properties props, String key, boolean fallback) {
		String value = props.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	private static float number(Properties props, String key, float fallback, float min, float max) {
		String value = props.getProperty(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Math.clamp(Float.parseFloat(value.trim()), min, max);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static <E extends Enum<E>> E choice(Properties props, String key, E fallback) {
		String value = props.getProperty(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(fallback.getDeclaringClass(), value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private EtnaConfig() {
	}
}
