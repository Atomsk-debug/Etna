package it.cosimo.etna;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Eye adaptation for the final pass: the Eye field of the post passes' EtnaSettings block. It is worked out
 * on the CPU from what the game already knows, so nothing is read back from the GPU: how much light reaches
 * the camera, and whether it is night out under the sky.
 *
 * The light at the eye is the sky light and block light of the camera's block, turned into brightness the
 * way vanilla's lightmap does it, the sky's part scaled by the time of day and the weather (the lightmap's
 * sky factor) and the block light's counting for a little less, as a torch lights a room less evenly than
 * the sky. The eyes follow it over a few seconds, opening up slowly as it gets darker and closing quickly
 * as it gets brighter (the idea of the smoothed eye brightness BSL and Complementary use, with Etna's own
 * curve). Exposure goes from DAY_EXPOSURE in full daylight to DARK_EXPOSURE with no light at all, gently:
 * caves and nights read a little better, noon is not washed out, and caves keep their mood. At night
 * under the open sky night vision sets in as well (tone.glsl), unless torchlight is around.
 *
 * The eye adaptation setting scales both, and at 0 the picture is as without them. Visibility (EtnaConfig)
 * opens the eyes further in the dark, up to VISIBILITY_EXPOSURE more with no light at all, fully from a
 * quarter of eye adaptation up and none with it off.
 *
 * All of it stays neutral with Etna off, in the Nether and the End, in water, lava
 * and powder snow (the water has its own look), and under Blindness or Darkness, which keep vanilla's dark;
 * with night vision exposure stays as in daylight.
 *
 * An error here turns only this off (neutral from then on), never the rest of the post effects.
 */
public final class EtnaEye {
	/** Exposure in full daylight: a little under 1, so a sunny noon keeps its colours. */
	private static final float DAY_EXPOSURE = 0.93F;
	/** Exposure with no light at all at the eye (a cave without torches): a third of a stop over 1. */
	private static final float DARK_EXPOSURE = 1.25F;
	/** Exposure Visibility at full adds with no light at the eye: half a stop more in a dark cave. */
	private static final float VISIBILITY_EXPOSURE = 0.45F;
	/** How much block light counts towards the light at the eye, against sky light. */
	private static final float BLOCK_WEIGHT = 0.6F;
	/** Seconds the eyes take (to 63 % of the way) to open up as it gets darker. */
	private static final float DARKEN_EASE = 3.0F;
	/** Seconds they take to close down as it gets brighter: stepping out of a cave glares only briefly. */
	private static final float BRIGHTEN_EASE = 0.8F;
	/** A gap between frames longer than this, in seconds, starts afresh at the target instead of easing. */
	private static final float GAP = 1.0F;

	/** The light at the eye (0 .. 1), the exposure and night vision (0 .. 1), eased: what Eye holds. */
	private static float light = 1.0F;
	private static float exposure = 1.0F;
	private static float night;
	/** What they are heading for this frame. */
	private static float targetLight = 1.0F;
	private static float targetExposure = 1.0F;
	private static float targetNight;
	/** System.nanoTime of the last update, 0 to start afresh. */
	private static long lastFrame;
	/** The level of the last update: a new one (another dimension, another world) starts afresh. */
	private static @Nullable ClientLevel lastLevel;
	/** Set after an error: neutral for the rest of the session. */
	private static boolean broken;

	private EtnaEye() {
	}

	/** Once per frame, before the settings block is written (EtnaLook.upload). */
	public static void update() {
		long now = System.nanoTime();
		float dt = lastFrame == 0L ? GAP + 1.0F : (now - lastFrame) / 1.0e9F;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		boolean fresh = dt > GAP || level != lastLevel;
		lastLevel = level;
		if (!EtnaConfig.enabled || broken || level == null) {
			light = 1.0F;
			exposure = 1.0F;
			night = 0.0F;
			// Switched back on, the eyes start where the light is.
			lastFrame = 0L;
			return;
		}
		lastFrame = now;
		try {
			aim(mc, level);
		} catch (RuntimeException e) {
			broken = true;
			light = 1.0F;
			exposure = 1.0F;
			night = 0.0F;
			EtnaClient.LOGGER.error("Etna: eye adaptation failed, it is off for this session", e);
			return;
		}
		if (fresh) {
			light = targetLight;
			exposure = targetExposure;
			night = targetNight;
			return;
		}
		light += (targetLight - light) * ease(dt, targetLight < light);
		night += (targetNight - night) * ease(dt, targetNight > night);
		// Exposure eases in stops, so opening up and closing down feel the same at every level.
		float stops = (float) Math.log(targetExposure / exposure);
		exposure *= (float) Math.exp(stops * ease(dt, targetExposure > exposure));
	}

	/** The share of the way to the target covered in dt seconds, slower when the eyes open up. */
	private static float ease(float dt, boolean darker) {
		return 1.0F - (float) Math.exp(-dt / (darker ? DARKEN_EASE : BRIGHTEN_EASE));
	}

	/** Works out this frame's targets. */
	private static void aim(Minecraft mc, ClientLevel level) {
		targetLight = 1.0F;
		targetExposure = 1.0F;
		targetNight = 0.0F;
		float strength = EtnaConfig.eyeAdaptation.value;
		float visibility = EtnaConfig.visibility.value;
		GameRenderState game = mc.gameRenderer.gameRenderState();
		CameraRenderState camera = game.levelRenderState.cameraRenderState;
		FogType medium = camera.fogType;
		if (strength <= 0.0F || !EtnaShadows.sunWorld() || medium == FogType.WATER || medium == FogType.LAVA
			|| medium == FogType.POWDER_SNOW || camera.entityRenderState.doesMobEffectBlockSky) {
			return;
		}
		if (mc.getCameraEntity() instanceof LivingEntity living && living.hasEffect(MobEffects.NIGHT_VISION)) {
			// Night vision already lights everything up as if by day.
			targetExposure = 1.0F + (DAY_EXPOSURE - 1.0F) * strength;
			return;
		}
		// camera.blockPos is the camera's own live position object: read it, never move it.
		BlockPos eye = camera.blockPos;
		float sky = brightness(level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(eye));
		float block = brightness(level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(eye));
		// 1 by day, 0.24 at night, less in a storm. Until the lightmap has been worked out once it reads 0:
		// daylight then, rather than eyes that start out wide open.
		float skyFactor = game.lightmapRenderState.skyFactor;
		if (skyFactor <= 0.0F) {
			skyFactor = 1.0F;
		}
		targetLight = Math.clamp(sky * skyFactor + BLOCK_WEIGHT * block, 0.0F, 1.0F);
		// By the square root, so the first steps into shade change little and the last of the light much.
		float dark = smoothstep(0.0F, 1.0F, 1.0F - (float) Math.sqrt(targetLight));
		targetExposure = 1.0F + (DAY_EXPOSURE + (DARK_EXPOSURE - DAY_EXPOSURE) * dark - 1.0F) * strength
			+ VISIBILITY_EXPOSURE * visibility * Math.min(4.0F * strength, 1.0F) * dark;
		// Night by the sun's height (twilight in between), seen where the sky reaches the eye; torchlight
		// around the eye keeps the colours.
		float nightTime = 1.0F - smoothstep(-0.15F, 0.05F, EtnaShadows.sunDirection().y);
		targetNight = nightTime * sky * (1.0F - smoothstep(0.2F, 0.6F, block)) * strength;
	}

	/** Vanilla's lightmap brightness for a light level 0 .. 15 (LightmapInfo's get_brightness), 0 .. 1. */
	private static float brightness(int level) {
		float l = Math.clamp(level, 0, 15) / 15.0F;
		return l / (4.0F - 3.0F * l);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** The Eye vec4 of the post passes' EtnaSettings block, bytes 336 to 351. */
	public static void writePost(Std140Builder builder) {
		builder.putVec4(new Vector4f(light, exposure, night, 0.0F));
	}
}
