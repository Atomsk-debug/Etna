package it.cosimo.etna;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/**
 * The water's reflections of the world (screen-space reflections, post/water_ssr.fsh), its shoreline foam and
 * its glitter (include/terrain_water.glsl): the values their shaders read, in the WaterSsr field of the post
 * passes' EtnaSettings block and the EtnaWaterSsr field of the terrain's EtnaShadow block.
 *
 * The reflections find the water by the picture's alpha. Etna's copies of the classic translucent terrain
 * pipelines (water, glass, ice: EtnaShadows.receiverFor) blend their colour exactly as vanilla's do, and
 * their alpha down to 1 - the surface's own alpha, where vanilla's blend raises it towards 1: everything
 * opaque leaves 1 there (terrain.fsh), so after the level only those surfaces leave less. Nothing reads the
 * picture's alpha before the world chain, which writes 1 everywhere again (copy_dither.fsh), and the final
 * chain does the same (copy_opaque.fsh). With Improved Transparency the water is drawn another way and leaves
 * no such mark, so the reflections stay off and the water keeps the sky's reflection alone.
 */
public final class EtnaWaterSsr {
	/** Classic translucent terrain's colour target: vanilla's translucent blend for colour, the water mask in alpha. */
	static final ColorTargetState MASK_TARGET = new ColorTargetState(
		new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA));
	/** Vanilla's GameTime runs over this many ticks (a day), and a tick is a twentieth of a second. */
	private static final long DAY_TICKS = 24000L;
	private static final float TICKS_PER_SECOND = 20.0F;

	/** The clock the water's waves run on this frame, in seconds: vanilla's GameTime * 1200. */
	private static float seconds;
	private static float cameraX;
	private static float cameraZ;
	/** The fraction of a block the camera stands above a whole block's height (camera y - floor(camera y)). */
	private static float cameraLevel;
	/** Estimate of vanilla's lightmap brightness under the open sky, 0 .. 1 (the terrain's etnaDaylight). */
	private static float daylight = 1.0F;
	/** Whether Improved Transparency's pipelines came through EtnaShadows.overrideFor since the last frame began. */
	private static boolean oitSeen;
	/** Whether the last frame was drawn with Improved Transparency. */
	private static boolean improvedTransparency;

	private EtnaWaterSsr() {
	}

	/** Once per frame from EtnaShadows.prepareFrame, before the terrain block and the post settings are written. */
	static void update(LevelRenderState state) {
		// The same time the terrain's waves run on: GameTime is ((game time % 24000) + partial tick) / 24000.
		seconds = ((state.gameTime % DAY_TICKS) + state.worldPartialTicks) / TICKS_PER_SECOND;
		Vec3 camera = state.cameraRenderState.pos;
		cameraX = (float) camera.x;
		cameraZ = (float) camera.z;
		cameraLevel = (float) (camera.y - Math.floor(camera.y));
		daylight = openSkyLight();
		improvedTransparency = oitSeen;
		oitSeen = false;
	}

	/**
	 * Vanilla's lightmap at full sky light and no block light, which the terrain's sky reflection on water is
	 * dimmed by: the sky light factor (1 by day, about 0.24 at night, less in storms) lifted by the Brightness
	 * option as the lightmap lifts it (1 - (1 - x)^4 at full Brightness). An estimate: the sky light's colour
	 * is taken as white. Until the lightmap has been worked out once the factor reads 0, and counts as day.
	 */
	private static float openSkyLight() {
		LightmapRenderState lightmap = Minecraft.getInstance().gameRenderer.gameRenderState().lightmapRenderState;
		float sky = lightmap.skyFactor > 0.0F ? Math.min(lightmap.skyFactor, 1.0F) : 1.0F;
		float lifted = 1.0F - (float) Math.pow(1.0F - sky, 4.0);
		return sky + (lifted - sky) * Math.clamp(lightmap.brightness, 0.0F, 1.0F);
	}

	/** EtnaShadows.overrideFor met one of Improved Transparency's terrain pipelines: this frame uses it. */
	static void sawImprovedTransparency() {
		oitSeen = true;
	}

	/**
	 * How strong the reflections are this frame: the water reflections setting while the water leaves its mask
	 * (Etna on, its terrain lighting working, classic transparency), else 0.
	 */
	private static float strength() {
		return EtnaConfig.enabled && !improvedTransparency && EtnaShadows.lightBlock() != null ? EtnaConfig.waterReflections.value : 0.0F;
	}

	/** The WaterSsr vec4 of the post passes' EtnaSettings block, bytes 512-527 (settings.glsl). */
	public static void writePost(Std140Builder builder) {
		builder.putVec4(new Vector4f(strength(), seconds, cameraX, cameraZ));
	}

	/** The EtnaWaterSsr vec4 of the terrain's EtnaShadow block, bytes 560-575 (terrain_ubo.glsl). */
	static void writeTerrain(Std140Builder builder) {
		builder.putVec4(new Vector4f(EtnaConfig.shoreFoam.value, EtnaConfig.waterGlitter.value, daylight, cameraLevel));
	}
}
