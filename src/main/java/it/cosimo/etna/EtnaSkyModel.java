package it.cosimo.etna;

/**
 * Etna's sky model on the Java side: a line-for-line port of assets/etna/shaders/include/sky.glsl (see
 * the technique there), for what is worked out once a frame instead of per pixel. EtnaSky asks it for
 * the per-frame part of the sky (the light reaching the air from the sun and the moon), the colour of the
 * sky in one direction (vanilla's fog colour) and the colours of the sunlight and of the skylight that
 * light the land. Plain float arithmetic, no allocation after the first call: it runs on the render
 * thread every frame. Directions are world-space unit vectors, y up.
 */
public final class EtnaSkyModel {
	private static final float[] LUMA = {0.2126F, 0.7152F, 0.0722F};
	private static final float[] RAYLEIGH = {0.0464F, 0.1085F, 0.2648F};
	private static final float HAZE = 0.022F;
	private static final float[] OZONE = {0.0120F, 0.0220F, 0.0013F};
	private static final float K_AIR = 796.0F;
	private static final float K_HAZE = 5300.0F;
	private static final float K_OZONE = 255.0F;
	private static final float EXPOSURE = 5.5F;
	private static final float[] NIGHT_ZENITH = {0.0060F, 0.0098F, 0.0228F};
	private static final float[] NIGHT_HORIZON = {0.0105F, 0.0135F, 0.0215F};
	private static final float[] MOON_TINT = {0.80F, 0.92F, 1.15F};
	private static final float[] MULTI_TINT = {0.85F, 0.92F, 1.08F};
	/** Blue-white light of the moon on the land, luma 1 (0.6.0's moonlight colour). */
	public static final float[] MOONLIGHT = {0.85F, 1.0F, 1.33F};

	/** The per-frame part of the sky (sky.glsl's EtnaSkyFrame). */
	public static final class Frame {
		public final float[] sun = new float[3];
		public final float[] moon = new float[3];
		public final float[] sunLow = new float[3];
		public final float[] sunHigh = new float[3];
		public final float[] multi = new float[3];
		public final float[] moonLow = new float[3];
		public final float[] moonHigh = new float[3];
		public final float[] overcast = new float[3];
		public float rain;
		public float storm;
		/** Strength of the haze's glow round the sun (the Sky Glow setting): 1 as tuned. */
		public float glow = 1.0F;
	}

	// Scratch space, render thread only.
	private static final float[] hue = new float[3];
	private static final float[] sample = new float[3];
	private static final float[] sum = new float[3];
	private static final float[] dir = new float[3];

	private EtnaSkyModel() {
	}

	static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	static float mix(float a, float b, float t) {
		return a + (b - a) * t;
	}

	static float luma(float[] c) {
		return LUMA[0] * c[0] + LUMA[1] * c[1] + LUMA[2] * c[2];
	}

	static float airMass(float y, float k) {
		return (float) Math.sqrt(k * k * y * y + 2.0F * k + 1.0F) - k * y;
	}

	static float phaseHG(float mu, float g) {
		float x = 1.0F + g * g - 2.0F * g * mu;
		return (1.0F - g * g) / (x * (float) Math.sqrt(x));
	}

	/** etnaSkyTransmittance: what is left of a light at elevation sine lightY on its way to the low (0) or high (1) air. */
	static void transmittance(float lightY, float high, float[] out) {
		float y = Math.max(lightY, 0.0F);
		float below = mix(1.0F, 0.3F, smoothstep(0.0F, -0.1F, lightY));
		float air = mix(1.0F, 0.15F, high) * below;
		float haze = mix(1.0F, 0.10F, high) * below;
		float airPath = airMass(y, K_AIR) * air;
		float hazeDepth = HAZE * 1.11F * airMass(y, K_HAZE) * haze;
		float ozonePath = airMass(y, K_OZONE);
		for (int i = 0; i < 3; i++) {
			out[i] = (float) Math.exp(-(RAYLEIGH[i] * airPath + hazeDepth + OZONE[i] * ozonePath));
		}
	}

	static float earthShadow(float lightY, float depth) {
		float y = Math.min(lightY, 0.0F);
		return (float) Math.exp(-398.0F / depth * y * y);
	}

	static float eyeAdapt(float sunY) {
		return 1.0F + 2.0F * (1.0F - smoothstep(-0.14F, 0.14F, sunY));
	}

	/** etnaSkyFrame. weather: 0 clear, 1 rain, 2 thunder; moonLight: the moon's brightness by phase (0 .. 1); glow: 1 as tuned. */
	public static void frame(Frame f, float[] sun, float[] moon, float weather, float moonLight, float glow) {
		System.arraycopy(sun, 0, f.sun, 0, 3);
		System.arraycopy(moon, 0, f.moon, 0, 3);
		float adapt = eyeAdapt(sun[1]);
		transmittance(sun[1], 0.45F, f.sunLow);
		scale(f.sunLow, earthShadow(sun[1], 1.0F) * adapt);
		transmittance(sun[1], 1.0F, f.sunHigh);
		scale(f.sunHigh, earthShadow(sun[1], 2.0F) * adapt);
		transmittance(sun[1], 0.6F, f.multi);
		scale(f.multi, earthShadow(sun[1], 2.0F) * adapt);
		for (int i = 0; i < 3; i++) {
			f.multi[i] *= MULTI_TINT[i];
		}
		float moonStrength = 0.025F * moonLight * smoothstep(-0.05F, 0.2F, moon[1]);
		transmittance(moon[1], 0.45F, f.moonLow);
		transmittance(moon[1], 1.0F, f.moonHigh);
		float lowShadow = earthShadow(moon[1], 1.0F) * moonStrength;
		float highShadow = earthShadow(moon[1], 2.0F) * moonStrength;
		for (int i = 0; i < 3; i++) {
			f.moonLow[i] *= MOON_TINT[i] * lowShadow;
			f.moonHigh[i] *= MOON_TINT[i] * highShadow;
		}
		float day = smoothstep(-0.20F, 0.35F, sun[1]);
		float level = mix(0.010F, 0.125F, day);
		f.overcast[0] = level * mix(0.82F, 0.95F, day);
		f.overcast[1] = level * mix(0.88F, 0.99F, day);
		f.overcast[2] = level * mix(1.00F, 1.04F, day);
		f.rain = Math.clamp(weather, 0.0F, 1.0F);
		f.storm = Math.clamp(weather - 1.0F, 0.0F, 1.0F);
		f.glow = glow;
	}

	private static void scale(float[] c, float s) {
		c[0] *= s;
		c[1] *= s;
		c[2] *= s;
	}

	/** etnaSkyScatter, added to out. */
	private static void scatter(float mu, float[] low, float[] high, float rise, float viewAirPath, float viewHaze, float glow, float[] out) {
		float phaseAir = 0.9F + 0.3F * mu * mu;
		float phaseHaze = (0.10F * phaseHG(mu, 0.80F) + 0.25F * phaseHG(mu, 0.55F) + 0.15F) * glow;
		for (int i = 0; i < 3; i++) {
			float viewAir = RAYLEIGH[i] * viewAirPath;
			float depth = viewAir + viewHaze * 1.11F;
			float fade = 1.0F - (float) Math.exp(-depth);
			float lit = mix(low[i], high[i], rise);
			out[i] += (lit * viewAir * phaseAir + low[i] * viewHaze * phaseHaze) / depth * fade;
		}
	}

	/** etnaSkyLinear: the sky's linear light in direction d. */
	static void linear(Frame f, float[] d, float[] out) {
		float y = Math.max(d[1], 0.0F) + 0.002F;
		float viewAirPath = airMass(y, K_AIR);
		float viewHaze = HAZE * airMass(y, K_HAZE);
		float steep = Math.min(y, 1.0F);
		float up = (float) Math.pow(steep, 0.35);
		float rise = (float) Math.pow(steep, 0.7);
		out[0] = 0.0F;
		out[1] = 0.0F;
		out[2] = 0.0F;
		float mu = Math.clamp(d[0] * f.sun[0] + d[1] * f.sun[1] + d[2] * f.sun[2], -1.0F, 1.0F);
		scatter(mu, f.sunLow, f.sunHigh, rise, viewAirPath, viewHaze, f.glow, out);
		float toward = 0.5F + 0.5F * mu;
		float multi = 0.04F + 0.12F * toward * toward * toward;
		for (int i = 0; i < 3; i++) {
			float depth = RAYLEIGH[i] * viewAirPath + viewHaze * 1.11F;
			out[i] += f.multi[i] * multi * (1.0F - (float) Math.exp(-depth));
		}
		if (f.moonLow[2] + f.moonHigh[2] > 1e-6F) {
			float muMoon = Math.clamp(d[0] * f.moon[0] + d[1] * f.moon[1] + d[2] * f.moon[2], -1.0F, 1.0F);
			scatter(muMoon, f.moonLow, f.moonHigh, rise, viewAirPath, viewHaze, f.glow, out);
		}
		float low = 1.0F - up;
		for (int i = 0; i < 3; i++) {
			out[i] += mix(NIGHT_ZENITH[i], NIGHT_HORIZON[i], low * low);
		}
		out[1] = Math.min(out[1], Math.max(out[0], out[2]));
		float grad = mix(1.12F, 0.92F, (float) Math.sqrt(steep));
		float dim = 1.0F - 0.45F * f.storm;
		for (int i = 0; i < 3; i++) {
			out[i] = mix(out[i], f.overcast[i] * grad, f.rain) * dim;
		}
	}

	/** etnaSkyDisplay, in place. */
	static void display(float[] c) {
		float peak = Math.max(Math.max(c[0], c[1]), Math.max(c[2], 1e-6F));
		float keep = (1.0F - (float) Math.exp(-peak * EXPOSURE)) / peak;
		for (int i = 0; i < 3; i++) {
			float each = 1.0F - (float) Math.exp(-c[i] * EXPOSURE);
			c[i] = mix(c[i] * keep, each, 0.35F);
		}
	}

	/** etnaSkyColor: the sky's colour on screen in direction d (a unit vector), without the sun disc and the stars. */
	public static void color(Frame f, float[] d, float[] out) {
		linear(f, d, out);
		display(out);
		float below = smoothstep(0.0F, -0.35F, d[1]);
		if (below > 0.0F) {
			float grey = luma(out);
			out[0] = mix(out[0], mix(out[0], grey * 0.92F, 0.35F) * 0.62F, below);
			out[1] = mix(out[1], mix(out[1], grey * 0.95F, 0.35F) * 0.62F, below);
			out[2] = mix(out[2], mix(out[2], grey, 0.35F) * 0.62F, below);
		}
	}

	/**
	 * Colour of the sunlight reaching the ground with the sun at elevation sine sunY, luma 1: white-gold at
	 * noon, gold low in the sky, a deep orange at the horizon but never a red filter over everything.
	 */
	public static void sunlight(float sunY, float[] out) {
		transmittance(sunY, 0.0F, out);
		float l = Math.max(luma(out), 1e-4F);
		for (int i = 0; i < 3; i++) {
			out[i] = mix(1.0F, out[i] / l, 0.80F);
		}
		float peak = Math.max(out[0], Math.max(out[1], out[2]));
		if (peak > 1.55F) {
			float keep = 0.55F / (peak - 1.0F);
			for (int i = 0; i < 3; i++) {
				out[i] = mix(1.0F, out[i], keep);
			}
		}
	}

	/**
	 * Colour of the light from the whole sky, luma 1: the upper half of the sky averaged (every direction
	 * counting the same, so the low sky where the sunset's colours lie lights the land as much as the
	 * zenith does), at 40 % of its saturation, and around sunset a third of the way to the sunset's own
	 * colour. weather as for frame.
	 */
	public static void skylight(Frame f, float weather, float[] out) {
		sum[0] = 0.0F;
		sum[1] = 0.0F;
		sum[2] = 0.0F;
		float total = 0.0F;
		for (int i = 0; i < 6; i++) {
			double el = (i + 0.5) / 6.0 * Math.PI / 2.0;
			float cosEl = (float) Math.cos(el);
			for (int j = 0; j < 16; j++) {
				double az = (j + 0.5) / 16.0 * Math.PI * 2.0;
				dir[0] = cosEl * (float) Math.cos(az);
				dir[1] = (float) Math.sin(el);
				dir[2] = cosEl * (float) Math.sin(az);
				color(f, dir, sample);
				for (int k = 0; k < 3; k++) {
					sum[k] += sample[k] * cosEl;
				}
				total += cosEl;
			}
		}
		for (int k = 0; k < 3; k++) {
			sum[k] /= total;
		}
		float l = Math.max(luma(sum), 1e-4F);
		float y = f.sun[1];
		float sunset = smoothstep(-0.12F, -0.01F, y) * (1.0F - smoothstep(0.08F, 0.35F, y)) * (1.0F - Math.min(weather, 1.0F));
		sunlight(Math.max(y, 0.0F), hue);
		for (int k = 0; k < 3; k++) {
			out[k] = mix(mix(1.0F, sum[k] / l, 0.40F), hue[k], 0.30F * sunset);
		}
	}
}
