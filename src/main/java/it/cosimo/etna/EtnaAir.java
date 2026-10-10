package it.cosimo.etna;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
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
 * Everything is 0 in dimensions without a sun and under Blindness and Darkness, which hide the world.
 *
 * An error here turns the mist and the shafts off for the session (nothing added), never anything else.
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
			if (!hasSun || level == null || camera.entityRenderState.doesMobEffectBlockSky) {
				clear();
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
		} catch (RuntimeException e) {
			broken = true;
			clear();
			EtnaClient.LOGGER.error("Etna: the mist and the light shafts failed, they are off for this session", e);
		}
	}

	/** Nothing in the air: no mist, no shafts, open sky. */
	private static void clear() {
		mist.set(0.0F);
		mistColor.set(0.0F);
		sunColor.set(0.0F);
		shaftAir = 0.0F;
		cover = 0.0F;
		cave = 0.0F;
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
	 * leave the shafts out and light the mist by the open sky instead.
	 */
	public static void writePost(Std140Builder builder) {
		boolean anything = shaftAir > 0.0F || mist.x > 0.0F || mist.y > 0.0F || mist.z > 0.0F;
		float radius = anything ? EtnaShadows.shaftRadius() : 0.0F;
		builder.putVec4(mist)
			.putVec4(mistColor)
			.putVec4(new Vector4f(radius > 0.0F ? shaftAir : 0.0F, cover, cave, radius))
			.putVec4(sunColor);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
