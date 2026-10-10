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

	private static boolean loaded;

	static {
		applyProfile(Profile.NATURAL);
		applyQuality(Quality.HIGH);
		visibility.set(VISIBILITY_DEFAULT);
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
		profiling = choice(props, "profiling", Profiling.OFF);
		shadowCulling = bool(props, "shadowCulling", true);
		shadowGather = bool(props, "shadowGather", true);
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
		props.setProperty("profiling", profiling.name().toLowerCase(Locale.ROOT));
		props.setProperty("shadowCulling", Boolean.toString(shadowCulling));
		props.setProperty("shadowGather", Boolean.toString(shadowGather));
		props.setProperty("quality", quality.name().toLowerCase(Locale.ROOT));
		props.setProperty("shadows", Boolean.toString(shadows));
		props.setProperty("shadowResolution", Integer.toString(shadowResolution));
		props.setProperty("shadowDistance", Float.toString(shadowDistance));
		props.setProperty("antiAliasing", Boolean.toString(antiAliasing));
		props.setProperty("profile", profile.name().toLowerCase(Locale.ROOT));
		for (Setting setting : LOOK) {
			props.setProperty(setting.key, Float.toString(setting.value));
		}
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
				+ " mist: 0 .. 1, ground and valley mist, night and rain haze, cave murk (0: off)");
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
