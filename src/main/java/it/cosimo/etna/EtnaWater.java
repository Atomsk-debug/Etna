package it.cosimo.etna;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * Water around the camera, for the terrain shader (the EtnaWater, EtnaWaterColor, EtnaWaterLook and
 * EtnaSkyColor fields of its block) and for the post passes (the Water field of EtnaSettings).
 *
 * Every frame it works out what the camera is in (air, water, lava, powder snow) and, under water, how far
 * the surface is above it, how open that surface is to the sky, the biome's water colour there and how far
 * the eyes have adapted (water vision). From those it takes over vanilla's water fog: the fog gets the
 * colour of the water itself (lit by the sky above, darker deep down, in a flooded cave and at night) and
 * reaches as far as the underwater visibility setting says, instead of vanilla's short, dark navy fog. The
 * light the water gets from the sky follows a change over a moment, the way eyes adjust, so swimming under
 * a dock or out of a cave never switches the fog at once. Everything vanilla fogs (entities, particles, the
 * sky, clouds) follows through the Fog block, and the terrain shader draws its own fog from the same values.
 *
 * Vanilla's fog stays as it is with Etna off, in lava and powder snow, with Blindness or Darkness and with a
 * boss's world fog: the terrain shader then sees a visibility of 0 and keeps vanilla's fog too.
 */
public final class EtnaWater {
	/** How far up the surface is looked for, in blocks; deeper than this counts as "deep down". */
	private static final int SCAN = 64;
	/** Vanilla's water colour (rivers, plains), until the biome's own has been read. */
	private static final int DEFAULT_WATER = 0x3F76E4;
	/** Seconds the sky's light in the water takes to follow a change (to 63 % of the way). */
	private static final float LIGHT_EASE = 0.75F;
	/** A gap between frames longer than this, in seconds, starts the light afresh instead of easing it. */
	private static final float LIGHT_GAP = 0.5F;

	/** What the camera is in: 0 air, 1 water, 2 lava, 3 powder snow. */
	private static int medium;
	/** The camera is in water and everything below was worked out this frame. */
	private static boolean underwater;
	/** LocalPlayer.getWaterVision: 0 right after diving in, 1 once the eyes have adapted (30 s). */
	private static float vision;
	/** Blocks from the camera up to the water surface above it, SCAN when it is deeper than that. */
	private static float surfaceAbove;
	/** How open the water surface above the camera is to the sky this frame, 0 (a flooded cave) .. 1 (open sea). */
	private static float skyOpen;
	/**
	 * skyOpen eased like surfaceLight, for the terrain shader: below 15 blocks the game's sky light is 0 in the
	 * open sea too, so the shader takes the camera's water as the guide to whether the deep water around is
	 * open (EtnaSkyColor.w). Eased so swimming out of a sea cave never relights the deep floor at once.
	 */
	private static float openSea;
	/**
	 * The sky's light reaching the water around the camera, 0 .. 1: skyOpen dimmed by the water between the
	 * surface and the camera, eased over LIGHT_EASE seconds.
	 */
	private static float surfaceLight;
	/** System.nanoTime of the last extract, 0 before the first. */
	private static long lastFrame;
	/** The biome's water colour at the camera, 0xRRGGBB, blended across biomes like the water itself. */
	private static int waterTint = DEFAULT_WATER;
	/** Etna's fog replaced vanilla's this frame. */
	private static boolean takeover;
	/** How far you see under water, in blocks: the setting, shortened while the eyes adapt. */
	private static float visibility;
	/** How bright the open sky is, 0 (night) .. 1 (day), from its colour. */
	private static float daylight;
	/** Colour of the water's fog: the light the water around the camera scatters towards it. */
	private static final Vector3f inscatter = new Vector3f();
	/** Colour of the open sky, as seen through the water surface from below. */
	private static final Vector3f sky = new Vector3f();
	/**
	 * Set after an error: the water stays as vanilla draws it (vanilla's fog, the shader's own look from before)
	 * for the rest of the session, instead of the error leaving the frame and taking the game down with it.
	 */
	private static boolean broken;

	private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	private EtnaWater() {
	}

	/**
	 * Once per frame, after the game has worked out the camera (GameRenderer.extractCamera): what the camera
	 * is in and, under water, the world around it. Vanilla reads the world here too, on the same thread.
	 */
	public static void extract(CameraRenderState camera) {
		long now = System.nanoTime();
		float dt = lastFrame == 0L ? LIGHT_GAP + 1.0F : (now - lastFrame) / 1.0e9F;
		lastFrame = now;
		boolean wasUnderwater = underwater;
		underwater = false;
		medium = 0;
		if (!EtnaConfig.enabled) {
			return;
		}
		// What the camera is in still counts after an error: the shader keeps the air's effects out of the water.
		FogType fogType = camera.fogType;
		medium = fogType == FogType.WATER ? 1 : fogType == FogType.LAVA ? 2 : fogType == FogType.POWDER_SNOW ? 3 : 0;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (broken || medium != 1 || level == null) {
			return;
		}
		try {
			// Vanilla brightens its water fog by the player's water vision only when the player is the camera.
			vision = mc.player != null && mc.getCameraEntity() == mc.player ? Math.clamp(mc.player.getWaterVision(), 0.0F, 1.0F) : 1.0F;
			// camera.blockPos is the camera's own live position object: read it, never move it.
			BlockPos eye = camera.blockPos;
			int x = eye.getX();
			int y = eye.getY();
			int z = eye.getZ();
			surfaceAbove = scanSurface(level, x, y, z, camera.pos.y);
			// The fog's light, eased so it never jumps: right after diving in (or after a long gap) it starts
			// where it is.
			float light = skyOpen * (float) Math.exp(-0.035F * Math.max(surfaceAbove, 0.0F));
			if (!wasUnderwater || dt > LIGHT_GAP) {
				surfaceLight = light;
				openSea = skyOpen;
			} else {
				float ease = 1.0F - (float) Math.exp(-dt / LIGHT_EASE);
				surfaceLight += (light - surfaceLight) * ease;
				openSea += (skyOpen - openSea) * ease;
			}
			cursor.set(x, y, z);
			waterTint = level.getBlockTint(cursor, BiomeColors.WATER_COLOR_RESOLVER) & 0xFFFFFF;
			underwater = true;
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** Stops the water for this session after an error: from now on vanilla's fog stays, and the shader with it. */
	private static void fail(RuntimeException e) {
		broken = true;
		underwater = false;
		takeover = false;
		EtnaClient.LOGGER.error("Etna: the water failed, vanilla's water fog is back for this session", e);
	}

	/**
	 * Walks up the water column from the camera's block to the surface, as Camera.calculateFogType measures
	 * the water at the camera (FluidState.getHeightForCamera), and reads how open the top of the water is to
	 * the sky (skyOpen): open sea fully, a lake in a cave not at all. Returns the surface's height above the
	 * eye.
	 */
	private static float scanSurface(ClientLevel level, int x, int y, int z, double eyeY) {
		for (int i = 0; i < SCAN; i++) {
			cursor.set(x, y + i, z);
			FluidState fluid = level.getFluidState(cursor);
			if (!fluid.is(FluidTags.WATER)) {
				// The water ends at the bottom of this block, and this block is what lies over it: a source
				// block under something sturdy (the plank of a dock, a cave's roof) counts as full.
				skyOpen = topOpen(level, x, y + i - 1, z);
				return (float) (y + i - eyeY);
			}
			float height = fluid.getHeightForCamera(level, cursor);
			if (height < 1.0F) {
				// The top of the column: the surface is inside this block.
				skyOpen = topOpen(level, x, y + i, z);
				return (float) (y + i + height - eyeY);
			}
		}
		// Deep down: too far to look. Deep water is open sea far more often than a flooded cave.
		skyOpen = 1.0F;
		return SCAN;
	}

	/**
	 * How open the top of the water, the block at (x, top, z), is to the sky, 0 .. 1: the sky light in that
	 * block (the water takes a level, so 14 there is open sky) or in the block above, whichever is brighter.
	 * Sky light spreads sideways a level per block, so under a plank, a dock or a bridge the top of the water
	 * stays nearly as bright as in the open and only dims a few blocks under a wide cover, while a lake in a
	 * cave gets none. Squared, so a few levels short of full already reads as shade.
	 */
	private static float topOpen(ClientLevel level, int x, int top, int z) {
		cursor.set(x, top, z);
		float inWater = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(cursor) / 14.0F;
		cursor.set(x, top + 1, z);
		float above = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(cursor) / 15.0F;
		float open = Math.clamp(Math.max(inWater, above), 0.0F, 1.0F);
		return open * open;
	}

	/**
	 * Once per frame, right before vanilla uploads this frame's fog (FogRenderer.updateBuffer in renderLevel):
	 * the sky has been extracted by now. Under water, this frame's FogData (vanilla makes a new one every
	 * frame) gets the water's colour and Etna's distance. Nothing carries over to the next frame, so switching
	 * Etna off with F7 gives vanilla's fog back on the very next one.
	 */
	public static void beforeFogUpload(LevelRenderState state) {
		takeover = false;
		if (!EtnaConfig.enabled || broken) {
			return;
		}
		try {
			takeOverFog(state);
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	private static void takeOverFog(LevelRenderState state) {
		readSky(state.skyRenderState);
		if (!underwater) {
			return;
		}
		CameraRenderState camera = state.cameraRenderState;
		// Green is down to 5 % at this distance. While the eyes adapt it starts at 60 % of it, where vanilla
		// starts at a quarter of its own.
		visibility = EtnaConfig.underwaterVisibility.value * Mth.lerp(vision, 0.6F, 1.0F);

		// The fog takes the biome's water colour (what the player sees as the water: blue in the ocean,
		// turquoise in warm oceans, green in swamps), at a brightness set by the light reaching the water:
		// the sky, dimmed by the water above the camera and by whatever covers its surface (surfaceLight).
		// Never black, even at night; dimmer for a fresh dive while the eyes adapt.
		float r = (waterTint >> 16 & 255) / 255.0F;
		float g = (waterTint >> 8 & 255) / 255.0F;
		float b = (waterTint & 255) / 255.0F;
		float luma = Math.max(0.2126F * r + 0.7152F * g + 0.0722F * b, 0.02F);
		float bright = (0.04F + 0.30F * daylight * surfaceLight) * Mth.lerp(vision, 0.7F, 1.0F);
		inscatter.set(r, g, b).mul(bright / luma);
		float peak = Math.max(inscatter.x, Math.max(inscatter.y, inscatter.z));
		if (peak > 1.0F) {
			inscatter.div(peak);
		}

		FogData fog = camera.fogData;
		if (camera.entityRenderState.doesMobEffectBlockSky || fog.shouldCreateBossFog) {
			// Blindness, Darkness and a boss's world fog are meant to hide things: they keep vanilla's.
			return;
		}
		fog.color.set(inscatter.x, inscatter.y, inscatter.z, 1.0F);
		// Vanilla's linear fog for everything the terrain shader doesn't draw (entities, particles, the sky,
		// clouds) ends where it matches the terrain's own curve within a few percent. Its start stays
		// vanilla's.
		float end = 0.75F * visibility;
		fog.environmentalEnd = end;
		fog.skyEnd = end;
		fog.cloudEnd = end;
		takeover = true;
	}

	/**
	 * The sky's colour and how bright it is. Only an overworld-style sky has a colour (SkyRenderState leaves
	 * it alone in the Nether and the End): without one the sky counts as dark.
	 */
	private static void readSky(SkyRenderState skyState) {
		if (skyState.skybox != DimensionType.Skybox.OVERWORLD) {
			sky.set(0.0F, 0.0F, 0.0F);
			daylight = 0.25F;
			return;
		}
		Vector3fc color = skyState.skyColor;
		if (color == null) {
			return;
		}
		// Rain greys the sky, as vanilla greys its fog colour.
		float rain = Math.clamp(1.0F - skyState.rainBrightness, 0.0F, 1.0F);
		sky.set(color).mul(1.0F - 0.5F * rain);
		float skyLuma = 0.2126F * sky.x + 0.7152F * sky.y + 0.0722F * sky.z;
		daylight = Math.clamp(skyLuma / 0.55F, 0.0F, 1.0F);
	}

	/** The four water vec4s of the terrain block, bytes 176 to 239: EtnaWater, EtnaWaterColor, EtnaWaterLook, EtnaSkyColor. */
	public static void writeTerrain(Std140Builder builder) {
		builder.putVec4(new Vector4f(medium, underwater ? vision : 0.0F, underwater ? surfaceAbove : 0.0F, 0.0F))
			.putVec4(new Vector4f(inscatter.x, inscatter.y, inscatter.z, takeover ? visibility : 0.0F))
			.putVec4(new Vector4f(EtnaConfig.waterClarity.value, EtnaConfig.caustics.value, EtnaConfig.underwaterRays.value, 0.0F))
			.putVec4(new Vector4f(sky.x, sky.y, sky.z, underwater ? openSea : 0.0F));
	}

	/** The Water vec4 of the post passes' EtnaSettings block, bytes 320 to 335. */
	public static void writePost(Std140Builder builder) {
		builder.putVec4(new Vector4f(medium, underwater ? vision : 0.0F, underwater ? surfaceAbove : 0.0F, takeover ? visibility : 0.0F));
	}
}
