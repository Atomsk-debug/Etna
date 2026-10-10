package it.cosimo.etna;

import java.util.IdentityHashMap;
import java.util.Map;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/**
 * Small touches for the terrain shader: the light held in the player's hand, and ground that stays wet for a
 * while after the rain. They fill the EtnaHand and EtnaHandPos fields of its block, bytes 256 to 287.
 *
 * Held light. Whatever gives off light as a block gives it off from either hand too: torches, lanterns,
 * glowstone, sea lanterns, shroomlight, froglights, campfires, end rods, jack o'lanterns, and lava in a bucket.
 * Its level (EtnaHand.w) is the light the block gives off as it is first placed, the brighter of the two
 * hands; the terrain shader lets it into vanilla's light around the player (terrain_touch.glsl,
 * terrain.vsh), never brighter than the same block placed there. Its colour (EtnaHand.rgb) is a tint over
 * the flame colour the shader gives torch light: white for fire, so a held torch looks like a placed one,
 * blue for soul fire, green for copper, cool for sea lanterns and end rods, violet for amethyst and crying
 * obsidian, red for redstone. The light sits at the hand: below the eyes, a little forward and out to the
 * side of the arm holding it (EtnaHandPos.xyz, relative to the camera, so in third person it stays with the
 * player). Torches and campfires go out under water. Nothing in spectator mode; with Etna off the block is
 * not bound and there is no held light either. The level eases in and out over a moment, so scrolling
 * through the hotbar past a torch doesn't flash.
 *
 * Wet ground. EtnaHandPos.w is how wet the ground is: it soaks up rain over about ten seconds (vanilla's
 * rain itself fades in over five) and once the rain stops it dries over a minute and a half, at a steady
 * pace, as a puddle does. terrain.fsh reads it where it used to read the rain (wetness); everything else
 * that follows the weather (the light, the sky, the post passes) keeps vanilla's rain level as it is
 * (EtnaWorld.w, EtnaShadows.rain). It stands still while the game is paused, and after a long gap (Etna
 * switched off, a loading screen) it starts again at the rain as it is.
 *
 * An error here turns only this off for the rest of the session (no held light, the ground exactly as wet as
 * the rain), never the rest of the terrain lighting.
 */
public final class EtnaTouch {
	/** Seconds the ground takes to soak up the rain, to 63 % of the way. */
	private static final float SOAK = 3.5F;
	/** Seconds soaked ground takes to dry once the rain has stopped. */
	private static final float DRY = 90.0F;
	/** Seconds the held light takes to follow a change of item, to 63 % of the way. */
	private static final float HAND_EASE = 0.08F;
	/** A gap between frames longer than this, in seconds, starts both afresh instead of easing them. */
	private static final float GAP = 1.0F;
	/** Where the held light sits, from the eyes, in blocks: down, forwards, and out towards the arm holding it. */
	private static final double HAND_DOWN = 0.4;
	private static final double HAND_FORWARD = 0.25;
	private static final double HAND_SIDE = 0.3;

	/** What an item gives off when held: its light level, its tint over the flame, and whether water puts it out. */
	private record Source(int level, float red, float green, float blue, boolean flame) {
		static final Source NONE = new Source(0, 1.0F, 1.0F, 1.0F, false);
	}

	/** Each item's Source, worked out the first time it is held. Render thread only. */
	private static final Map<Item, Source> SOURCES = new IdentityHashMap<>();

	/** This frame's EtnaHand and EtnaHandPos. */
	private static final Vector4f hand = new Vector4f(1.0F, 1.0F, 1.0F, 0.0F);
	private static final Vector4f handPos = new Vector4f();
	private static float wetness;
	/** System.nanoTime of the last update, 0 before the first. */
	private static long lastFrame;
	/** Set after an error: no held light and the ground as wet as the rain, for the rest of the session. */
	private static boolean broken;

	private EtnaTouch() {
	}

	/**
	 * Once per frame, before the terrain block is written (EtnaShadows.prepareFrame). rain is vanilla's rain
	 * level as the terrain block carries it (0 .. 1, 0 in a dimension without a sun).
	 */
	public static void update(LevelRenderState state, float rain) {
		long now = System.nanoTime();
		float dt = lastFrame == 0L ? GAP + 1.0F : (now - lastFrame) / 1.0e9F;
		lastFrame = now;
		if (broken) {
			handPos.w = rain;
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			boolean fresh = dt > GAP;
			if (fresh) {
				wetness = rain;
			} else if (!mc.isPaused()) {
				wetness = rain > wetness
					? wetness + (rain - wetness) * (1.0F - (float) Math.exp(-dt / SOAK))
					: Math.max(rain, wetness - dt / DRY);
			}
			updateHand(mc, state.cameraRenderState, dt, fresh);
			handPos.w = Math.clamp(wetness, 0.0F, 1.0F);
		} catch (RuntimeException e) {
			broken = true;
			hand.set(1.0F, 1.0F, 1.0F, 0.0F);
			handPos.set(0.0F, 0.0F, 0.0F, rain);
			EtnaClient.LOGGER.error("Etna: the held light and the wet ground failed, they are off for this session", e);
		}
	}

	private static void updateHand(Minecraft mc, CameraRenderState camera, float dt, boolean fresh) {
		LocalPlayer player = mc.player;
		Source source = Source.NONE;
		if (player != null && !player.isSpectator()) {
			Source main = sourceOf(player.getMainHandItem(), player);
			Source off = sourceOf(player.getOffhandItem(), player);
			HumanoidArm arm = player.getMainArm();
			if (off.level() > main.level()) {
				source = off;
				arm = arm.getOpposite();
			} else {
				source = main;
			}
			if (source.level() > 0) {
				float partialTicks = camera.cameraEntityPartialTicks;
				Vec3 eye = player.getEyePosition(partialTicks);
				double yaw = Math.toRadians(player.getViewYRot(partialTicks));
				// Looking along (x, z) on the ground, the player's right is (-z, x).
				double forwardX = -Math.sin(yaw);
				double forwardZ = Math.cos(yaw);
				double side = arm == HumanoidArm.RIGHT ? HAND_SIDE : -HAND_SIDE;
				handPos.x = (float) (eye.x - camera.pos.x + forwardX * HAND_FORWARD - forwardZ * side);
				handPos.y = (float) (eye.y - camera.pos.y - HAND_DOWN);
				handPos.z = (float) (eye.z - camera.pos.z + forwardZ * HAND_FORWARD + forwardX * side);
				hand.x = source.red();
				hand.y = source.green();
				hand.z = source.blue();
			}
		}
		// The light fades out where it was last held, in its last colour.
		float target = source.level();
		float level = fresh ? target : hand.w + (target - hand.w) * (1.0F - (float) Math.exp(-dt / HAND_EASE));
		hand.w = level < 0.01F ? 0.0F : level;
	}

	/** What a held stack gives off; a torch or a campfire under water gives off nothing. */
	private static Source sourceOf(ItemStack stack, LocalPlayer player) {
		if (stack.isEmpty()) {
			return Source.NONE;
		}
		Source source = SOURCES.computeIfAbsent(stack.getItem(), EtnaTouch::lookUp);
		return source.flame() && player.isUnderWater() ? Source.NONE : source;
	}

	/**
	 * An item's light, from the block it places (as that block is first placed: a lit campfire, an unlit candle)
	 * or, for lava in a bucket, the lava's. The tint goes by the item's name, so every copper and soul variant
	 * gets its colour, and mods' items named the same way too.
	 */
	private static Source lookUp(Item item) {
		int level = item == Items.LAVA_BUCKET ? 15
			: item instanceof BlockItem block ? block.getBlock().defaultBlockState().getLightEmission() : 0;
		if (level <= 0) {
			return Source.NONE;
		}
		String name = BuiltInRegistries.ITEM.getKey(item).getPath();
		boolean flame = name.contains("torch") || name.contains("campfire");
		if (name.contains("soul")) {
			return new Source(level, 0.3F, 1.05F, 3.0F, flame);
		}
		if (name.contains("sculk")) {
			return new Source(level, 0.45F, 1.1F, 1.9F, flame);
		}
		if (name.contains("copper_torch") || name.contains("copper_lantern")) {
			return new Source(level, 0.6F, 1.5F, 0.75F, flame);
		}
		if (name.contains("redstone")) {
			return new Source(level, 1.5F, 0.55F, 0.5F, flame);
		}
		if (name.contains("sea_lantern") || name.contains("end_rod") || name.contains("beacon")
				|| name.contains("conduit") || name.contains("sea_pickle")) {
			return new Source(level, 0.8F, 1.08F, 1.9F, flame);
		}
		if (name.contains("amethyst") || name.contains("crying_obsidian") || name.contains("respawn_anchor")) {
			return new Source(level, 1.2F, 0.7F, 2.3F, flame);
		}
		if (name.contains("verdant_froglight")) {
			return new Source(level, 0.85F, 1.3F, 0.8F, flame);
		}
		if (name.contains("pearlescent_froglight")) {
			return new Source(level, 1.15F, 0.85F, 1.4F, flame);
		}
		return new Source(level, 1.0F, 1.0F, 1.0F, flame);
	}

	/** The EtnaHand and EtnaHandPos vec4s of the terrain block, bytes 256 to 287. */
	public static void writeTerrain(Std140Builder builder) {
		builder.putVec4(hand).putVec4(handPos);
	}
}
