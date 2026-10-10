package it.cosimo.etna;

import com.mojang.blaze3d.buffers.Std140Builder;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The mood filter: a colour grade laid over the Look, matched to a family of resource packs or purely magic
 * (EtnaConfig.Mood, chosen on the Mood page). It fills the Mood0 .. Mood7 fields of the post passes'
 * EtnaSettings block; the final pass applies them (grade.glsl) and the world pass draws the Ink & Colour
 * outlines (ink.glsl).
 *
 * Each mood is one row of plain numbers (values below): exposure in stops, white balance in Kelvin (lower is
 * warmer, as if the scene were lit by a lower colour temperature), contrast, saturation and vibrance, hue
 * shifts in degrees and chroma for three colour bands (greens, blues, warm wood and earth), shadow and
 * highlight tints with their strengths, black lift, highlight softness, dreamy glow amount and radius,
 * halation, bloom tint, a vignette offset and ink outlines. Mood Strength fades every number towards
 * neutral, where the picture is the Look's own; Natural is Etna's own look with only a faint dreamy glow and
 * halation. Here the rows become what the shader needs (white balance gains, tints of luminance 1, radians),
 * so the shader stays a few multiplies.
 *
 * Two touches follow the world: the dreamy glow grows by half around sunrise and sunset, and in the Nether and
 * the End, whose colours are their own, the hue shifts stand down while the rest of the mood stays.
 */
public final class EtnaMood {
	// Columns of a mood row.
	private static final int EV = 0;
	private static final int KELVIN = 1;
	private static final int CONTRAST = 2;
	private static final int SATURATION = 3;
	private static final int VIBRANCE = 4;
	private static final int GREEN_SHIFT = 5;
	private static final int GREEN_CHROMA = 6;
	private static final int BLUE_SHIFT = 7;
	private static final int BLUE_CHROMA = 8;
	private static final int WARM_SHIFT = 9;
	private static final int WARM_CHROMA = 10;
	private static final int SHADOW_TINT = 11;
	private static final int SHADOW_STRENGTH = 14;
	private static final int HIGHLIGHT_TINT = 15;
	private static final int HIGHLIGHT_STRENGTH = 18;
	private static final int LIFT = 19;
	private static final int SOFTNESS = 20;
	private static final int GLOW = 21;
	private static final int GLOW_RADIUS = 22;
	private static final int HALATION = 23;
	private static final int BLOOM_TINT = 24;
	private static final int VIGNETTE = 27;
	private static final int INK = 28;
	private static final int COLUMNS = 29;

	/** The white balance every mood is measured from, and the one that leaves the picture as it is. */
	private static final float NEUTRAL_KELVIN = 6500.0F;
	/** The dreamy glow grows by this share at sunrise and sunset. */
	private static final float GOLDEN_GLOW = 0.5F;

	/** The row that leaves the picture as it is, in the column order of the moods' rows: where Mood Strength 0 lands. */
	private static final float[] NEUTRAL = {
		0.0F, NEUTRAL_KELVIN, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F,
		0.0F, 0.0F, 0.0F, 0.5F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F
	};

	private static final float[] blended = new float[COLUMNS];

	private EtnaMood() {
	}

	/**
	 * The mood's row, in the column order above. The numbers come from looking at the packs each mood is made
	 * for (docs/settings.md names them): vanilla-like packs want only a clean, slightly crisper grade;
	 * flat trailer packs saturated colour, cool clay shadows and a hint of outline; cosy storybook packs
	 * softer contrast, lifted warm-mauve blacks, olive greens and a strong glow; medieval packs deeper
	 * contrast, amber light against slate shadows and earthier greens and blues; comic packs saturated,
	 * clean colour with ink outlines; realistic packs a barely-there filmic grade. Enchanted, Mystic
	 * Twilight, Harvest Autumn and Dreamy Pastel are purely magic.
	 */
	private static float[] values(EtnaConfig.Mood mood) {
		return switch (mood) {
			// ev, kelvin, contrast, saturation, vibrance, green shift and chroma, blue shift and chroma, warm shift and
			// chroma, shadow tint and strength, highlight tint and strength, lift, softness, glow, glow radius, halation,
			// bloom tint, vignette, ink
			case NATURAL -> new float[] {0.0F, 6500F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.12F, 0.5F, 0.05F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F};
			case CLEAR_DAY -> new float[] {0.0F, 6500F, 1.05F, 1.05F, 0.1F, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.92F, 0.96F, 1.10F, 0.08F, 1.06F, 1.0F, 0.92F, 0.08F, 0.0F, 0.2F, 0.05F, 0.3F, 0.02F, 1.0F, 1.0F, 1.0F, -0.05F, 0.0F};
			case TRAILER -> new float[] {0.15F, 6800F, 1.0F, 1.15F, 0.2F, 6.0F, 1.0F, -5.0F, 1.0F, 0.0F, 1.0F, 0.90F, 0.92F, 1.15F, 0.12F, 1.05F, 1.02F, 0.94F, 0.08F, 0.025F, 0.3F, 0.10F, 0.3F, 0.0F, 1.0F, 1.0F, 1.0F, -0.1F, 0.15F};
			case STORYBOOK -> new float[] {0.1F, 5900F, 0.9F, 0.88F, 0.15F, -10.0F, 0.85F, 8.0F, 1.0F, 4.0F, 1.10F, 1.0F, 0.90F, 1.08F, 0.14F, 1.10F, 1.0F, 0.86F, 0.16F, 0.045F, 0.5F, 0.25F, 0.6F, 0.06F, 1.10F, 1.0F, 0.88F, 0.0F, 0.0F};
			case OLD_KINGDOM -> new float[] {-0.05F, 5600F, 1.10F, 0.95F, 0.05F, -8.0F, 0.8F, -6.0F, 0.8F, -3.0F, 1.05F, 0.90F, 0.98F, 1.06F, 0.12F, 1.12F, 1.0F, 0.80F, 0.18F, 0.0F, 0.2F, 0.08F, 0.5F, 0.05F, 1.15F, 0.98F, 0.80F, 0.1F, 0.0F};
			case INK -> new float[] {0.1F, 6500F, 1.10F, 1.20F, 0.1F, 0.0F, 1.0F, -4.0F, 1.0F, 0.0F, 1.0F, 0.88F, 0.90F, 1.18F, 0.12F, 1.02F, 1.0F, 0.98F, 0.04F, 0.0F, 0.15F, 0.05F, 0.3F, 0.0F, 1.0F, 1.0F, 1.0F, -0.25F, 0.55F};
			case LIFELIKE -> new float[] {0.0F, 6300F, 1.05F, 0.95F, 0.1F, -3.0F, 1.0F, -4.0F, 1.0F, 0.0F, 1.0F, 0.92F, 1.0F, 1.06F, 0.06F, 1.06F, 1.0F, 0.94F, 0.06F, 0.01F, 0.35F, 0.03F, 0.5F, 0.03F, 1.0F, 1.0F, 1.0F, -0.05F, 0.0F};
			case ENCHANTED -> new float[] {0.1F, 6500F, 0.95F, 1.08F, 0.2F, -6.0F, 1.0F, 8.0F, 1.0F, -5.0F, 1.10F, 1.0F, 0.88F, 1.10F, 0.15F, 1.10F, 0.97F, 0.98F, 0.22F, 0.03F, 0.5F, 0.30F, 0.7F, 0.08F, 1.15F, 0.95F, 1.0F, -0.05F, 0.0F};
			case MYSTIC -> new float[] {-0.1F, 7000F, 1.08F, 0.95F, 0.15F, 6.0F, 0.85F, 8.0F, 1.0F, 10.0F, 1.15F, 0.80F, 0.95F, 1.20F, 0.18F, 1.05F, 0.92F, 1.12F, 0.15F, 0.02F, 0.35F, 0.22F, 0.6F, 0.04F, 0.90F, 1.0F, 1.15F, 0.1F, 0.0F};
			case AUTUMN -> new float[] {0.0F, 5700F, 1.05F, 1.05F, 0.1F, -28.0F, 1.10F, -5.0F, 1.0F, -5.0F, 1.08F, 0.95F, 0.92F, 1.05F, 0.10F, 1.15F, 1.0F, 0.78F, 0.14F, 0.015F, 0.3F, 0.12F, 0.5F, 0.06F, 1.15F, 0.98F, 0.80F, 0.0F, 0.0F};
			case PASTEL -> new float[] {0.25F, 6600F, 0.85F, 0.80F, 0.25F, 2.0F, 0.75F, 8.0F, 1.0F, -4.0F, 1.0F, 0.95F, 0.92F, 1.10F, 0.10F, 1.08F, 1.0F, 1.02F, 0.10F, 0.045F, 0.6F, 0.35F, 0.8F, 0.03F, 1.05F, 1.0F, 1.08F, -0.15F, 0.0F};
		};
	}

	/**
	 * Mood0 .. Mood4, bytes 416-495 of the EtnaSettings block. Works out this frame's mood first, so call it
	 * before {@link #writePostTail}.
	 */
	public static void writePost(Std140Builder builder) {
		blend();
		float[] p = blended;

		// White balance: the colour of a black body at the mood's temperature against one at 6500 K, both in
		// linear sRGB, as per-channel gains (von Kries in RGB), scaled to luminance 1 and then by the exposure.
		Vector3f white = blackBody(p[KELVIN]);
		Vector3f neutral = blackBody(NEUTRAL_KELVIN);
		white.div(neutral);
		white.mul((float) Math.pow(2.0, p[EV]) / luma(white.x, white.y, white.z));
		builder.putVec4(new Vector4f(white, p[CONTRAST] - 1.0F));

		builder.putVec4(new Vector4f(p[SATURATION], p[VIBRANCE], p[SOFTNESS], p[VIGNETTE]));

		Vector3f shadow = tint(p, SHADOW_TINT);
		Vector3f shadowShift = new Vector3f(shadow).sub(1.0F, 1.0F, 1.0F).mul(p[SHADOW_STRENGTH]);
		builder.putVec4(new Vector4f(shadowShift, p[GLOW] * (1.0F + GOLDEN_GLOW * goldenHour())));

		Vector3f highlight = tint(p, HIGHLIGHT_TINT).sub(1.0F, 1.0F, 1.0F).mul(p[HIGHLIGHT_STRENGTH]);
		builder.putVec4(new Vector4f(highlight, p[GLOW_RADIUS]));

		// The black lift takes the shadows' tint.
		Vector3f lift = shadow.mul(p[LIFT]).max(new Vector3f());
		builder.putVec4(new Vector4f(lift, p[HALATION]));
	}

	/** Mood5 .. Mood7, bytes 528-575 of the EtnaSettings block, after {@link #writePost}. */
	public static void writePostTail(Std140Builder builder) {
		float[] p = blended;
		builder.putVec4(new Vector4f(tint(p, BLOOM_TINT), p[INK]));

		// The Nether and the End keep their own hues.
		float hues = EtnaShadows.sunWorld() ? 1.0F : 0.0F;
		float greenShift = hues * radians(p[GREEN_SHIFT]);
		float blueShift = hues * radians(p[BLUE_SHIFT]);
		float warmShift = hues * radians(p[WARM_SHIFT]);
		float greenChroma = hues * (p[GREEN_CHROMA] - 1.0F);
		float blueChroma = hues * (p[BLUE_CHROMA] - 1.0F);
		float warmChroma = hues * (p[WARM_CHROMA] - 1.0F);
		builder.putVec4(new Vector4f(greenShift, greenChroma, blueShift, blueChroma));

		boolean work = Math.abs(p[SATURATION] - 1.0F) > 0.001F || p[VIBRANCE] > 0.001F
			|| Math.abs(greenShift) > 0.0001F || Math.abs(greenChroma) > 0.001F
			|| Math.abs(blueShift) > 0.0001F || Math.abs(blueChroma) > 0.001F
			|| Math.abs(warmShift) > 0.0001F || Math.abs(warmChroma) > 0.001F;
		builder.putVec4(new Vector4f(warmShift, warmChroma, work ? 1.0F : 0.0F, 0.0F));
	}

	/**
	 * This frame's mood: its row faded towards neutral by Mood Strength. The white balance fades in mireds
	 * (millions over the temperature), the scale on which equal steps look like equal changes of colour.
	 */
	private static void blend() {
		float[] target = values(EtnaConfig.mood);
		float s = EtnaConfig.moodStrength.value;
		for (int i = 0; i < COLUMNS; i++) {
			blended[i] = NEUTRAL[i] + (target[i] - NEUTRAL[i]) * s;
		}
		float neutralMired = 1.0e6F / NEUTRAL[KELVIN];
		blended[KELVIN] = 1.0e6F / (neutralMired + (1.0e6F / target[KELVIN] - neutralMired) * s);
	}

	/** The tint starting at column {@code at}, scaled to luminance 1, so it shifts hue and leaves brightness. */
	private static Vector3f tint(float[] p, int at) {
		float l = luma(p[at], p[at + 1], p[at + 2]);
		return new Vector3f(p[at], p[at + 1], p[at + 2]).div(Math.max(l, 1.0e-4F));
	}

	/**
	 * Around sunrise and sunset, 0 .. 1: up from the sun 0.02 under the horizon, full while it is between 0.1
	 * and the horizon, gone once it is 0.4 up (its direction's height). 0 without a sun.
	 */
	private static float goldenHour() {
		if (!EtnaShadows.sunWorld()) {
			return 0.0F;
		}
		float y = EtnaShadows.sunDirection().y;
		return smoothstep(-0.12F, 0.02F, y) * (1.0F - smoothstep(0.1F, 0.4F, y));
	}

	/**
	 * The colour of a black body at this temperature, in linear sRGB (luminance 1): its chromaticity on the
	 * Planckian locus by the cubic fit of Kim et al. (2002, US patent 7024034), turned into XYZ and then sRGB.
	 */
	private static Vector3f blackBody(float kelvin) {
		double t = Math.clamp(kelvin, 1667.0F, 25000.0F);
		double t2 = t * t;
		double t3 = t2 * t;
		double x = t <= 4000.0
			? -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
			: -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390;
		double y;
		if (t <= 2222.0) {
			y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
		} else if (t <= 4000.0) {
			y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
		} else {
			y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
		}
		double bigX = x / y;
		double bigZ = (1.0 - x - y) / y;
		return new Vector3f(
			(float) (3.2404542 * bigX - 1.5371385 - 0.4985314 * bigZ),
			(float) (-0.9692660 * bigX + 1.8760108 + 0.0415560 * bigZ),
			(float) (0.0556434 * bigX - 0.2040259 + 1.0572252 * bigZ));
	}

	private static float luma(float r, float g, float b) {
		return 0.2126F * r + 0.7152F * g + 0.0722F * b;
	}

	private static float radians(float degrees) {
		return degrees * ((float) Math.PI / 180.0F);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
