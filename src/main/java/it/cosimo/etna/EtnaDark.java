package it.cosimo.etna;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector4f;

/**
 * Dark places, for the terrain shader: the EtnaDark field of its block.
 *
 * x is the cave light, the least light a place the sky can't reach gets (terrain_dark_vertex.glsl), in
 * lightmap units: a quarter of the setting's square root, and none in dimensions without a sun, whose own
 * dark would only turn blue. The square root because vanilla's own floor counts towards that least light,
 * and at the default Brightness (50 %) the floor is already about 0.09 in the overworld: a straight quarter
 * of the looks' 0.3 to 0.5 left only a level-step of light or none at all. Through the root the looks give
 * 0.14 to 0.18, a cool light that shows at the default Brightness and more so on Moody, while Bright
 * (a floor of about 0.15) and night vision still get next to nothing. y is the glowing blocks setting
 * (terrain_dark.glsl), and 0 with smooth lighting off: then every face that touches a light's cell reads
 * the light's own level, so the shader can't tell a lantern from the ceiling above it. z is kept for
 * glowing ores (0). w is 1 normally and 0 under Darkness or Blindness, which takes both away so those
 * effects stay as dark as vanilla makes them.
 *
 * An error here turns only this off (nothing added from then on), never the rest of the terrain lighting.
 */
public final class EtnaDark {
	/** This frame's EtnaDark; until the first frame, nothing added. */
	private static final Vector4f terrain = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	/** Set after an error: EtnaDark stays at nothing added for the rest of the session. */
	private static boolean broken;

	private EtnaDark() {
	}

	/** Once per frame, before the terrain block is written (EtnaShadows.prepareFrame). */
	public static void update(LevelRenderState state) {
		if (broken) {
			return;
		}
		try {
			SkyRenderState sky = state.skyRenderState;
			// The same test EtnaShadows uses for a dimension with a sun.
			boolean hasSun = sky.skybox != DimensionType.Skybox.NONE && sky.skybox != DimensionType.Skybox.END;
			// The option the chunk meshes are built with (LevelRenderer.invalidateCompiledGeometry); changing it
			// rebuilds them.
			boolean smoothLighting = Minecraft.getInstance().options.ambientOcclusion().get();
			terrain.set(
				hasSun ? EtnaConfig.caveLightFloor() : 0.0F,
				smoothLighting ? EtnaConfig.emission.value : 0.0F,
				0.0F,
				1.0F - darkEffect(state.cameraRenderState)
			);
		} catch (RuntimeException e) {
			broken = true;
			terrain.set(0.0F, 0.0F, 0.0F, 1.0F);
			EtnaClient.LOGGER.error("Etna: the cave light and glowing blocks failed, they are off for this session", e);
		}
	}

	/**
	 * How far Darkness or Blindness has taken hold of the camera's entity, 0 .. 1. Darkness fades in and
	 * out as vanilla's own darkening does (the same blend factor its lightmap uses), so the cave light
	 * doesn't vanish in one frame when a sculk shrieker goes off. Blindness switches it off at once.
	 */
	private static float darkEffect(CameraRenderState camera) {
		if (!camera.entityRenderState.doesMobEffectBlockSky) {
			return 0.0F;
		}
		if (Minecraft.getInstance().getCameraEntity() instanceof LivingEntity living && !living.hasEffect(MobEffects.BLINDNESS)) {
			return Math.clamp(living.getEffectBlendFactor(MobEffects.DARKNESS, camera.cameraEntityPartialTicks), 0.0F, 1.0F);
		}
		return 1.0F;
	}

	/** The EtnaDark vec4 of the terrain block, bytes 240 to 255. */
	public static void writeTerrain(Std140Builder builder) {
		builder.putVec4(terrain);
	}
}
