package it.cosimo.etna;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Fireflies and magic motes, without particles: up to 32 points of light the game places around the camera
 * and the last world pass draws (post/copy_dither.fsh), after the anti-aliasing so they never leave trails.
 *
 * Placing them (every client tick, while Etna is on and a world is loaded). A few columns around the camera
 * are looked at each tick, at random within RADIUS blocks, from a little above the eyes down to the first
 * ground with open air or a plant over it; leaves are looked through, so trees never carry fireflies on top
 * and the forest floor under them does. What the column holds decides the mote:
 * - Fireflies, in the Overworld under the open sky (sky light 8 or more), from dusk to dawn and not in the
 *   rain: over grass, ferns, flowers and other plants, three times as many over firefly bushes, a few over
 *   bare grass, more where water is near (swamps, river banks). Warm yellow-green, they wander gently
 *   around where they were placed and each flashes softly every four to seven seconds, rising a little as
 *   real fireflies do.
 * - Pale motes under the Pale Garden's trees or over its moss: grey-white, drifting slowly down from the
 *   canopy, at any hour (dim by day), never flashing.
 * - Spores in lush caves (no sky light to speak of, over moss, azalea or dripleaf, many more under spore
 *   blossoms): yellow-green, rising slowly.
 * - Soul wisps over soul sand and soul soil in the Nether: cyan, circling as they rise.
 * - End motes over end stone and chorus: violet, drifting.
 * Each lives 20 to 60 seconds, fading in and out over two, and fades out early once it is left behind or its
 * night is over. The Fireflies setting (Effects page) sets how many there are at most, for every kind.
 *
 * Drawing them (every frame, right before the post passes' settings are written). Each mote's place is
 * worked out from a smooth function of time, in double precision around the camera, so nothing jitters far
 * from the world's origin and nothing is re-rolled from frame to frame. It is then projected onto the screen
 * here, where its size is worked out too: a core a hundredth of a block or so wide (by kind), never narrower
 * than a pixel (then dimmer, so a far mote doesn't grow brighter), and a halo about a third of a block wide,
 * kept between two shares of the screen's height, as the eye's own glare round a small light keeps its size. Motes off screen or nearly
 * invisible are left out. The screen is cut into TILES_X x TILES_Y tiles, and each tile gets a mask of the
 * motes whose halo reaches into it, so the shader only looks at the motes near each pixel. All of it goes
 * into the EtnaMotes block (BLOCK), a buffer of its own that PostPassMixin hands the last world pass; the
 * Motes field of EtnaSettings (writePost) holds how many there are, and at 0 the pass skips them entirely.
 *
 * With Etna off, the setting off or no world pass running (Low quality), nothing is placed, nothing is drawn
 * and no work is done. An error turns the motes off for the rest of the session, never anything else.
 */
public final class EtnaMotes {
	/** The last world pass's uniform block of motes (PostPassMixin swaps this buffer in for the chain's placeholder). */
	public static final String BLOCK = "EtnaMotes";

	/** Most motes at once: one bit each in a tile's mask. copy_dither.fsh has the same. */
	private static final int MAX = 32;
	/** How many motes each Fireflies setting keeps around at most (Off, Few, Normal, Many). */
	private static final int[] TARGET = {0, 8, 18, MAX};
	/** The tiles the screen is cut into. copy_dither.fsh has the same. */
	private static final int TILES_X = 32;
	private static final int TILES_Y = 18;
	/** std140 layout of the block: three vec4[MAX] arrays, then one uint per tile. */
	private static final int POS_OFFSET = 0;
	private static final int COLOR_OFFSET = MAX * 16;
	private static final int SHAPE_OFFSET = 2 * MAX * 16;
	private static final int TILES_OFFSET = 3 * MAX * 16;
	private static final int SIZE = TILES_OFFSET + TILES_X * TILES_Y * 4;

	/** Motes are placed within this many blocks of the camera (horizontally) and fade out past LEAVE. */
	private static final double RADIUS = 24.0;
	private static final double LEAVE = RADIUS + 6.0;
	/** Never closer than this to the camera when placed: one appearing in your face would startle. */
	private static final double MIN_RADIUS = 3.0;
	/** Each column is looked at from this many blocks above the eyes to this many below. */
	private static final int SCAN_UP = 6;
	private static final int SCAN_DOWN = 12;
	/** Columns looked at per tick while there are fewer motes than wanted, and new motes per tick at most. */
	private static final int SAMPLES_PER_TICK = 6;
	private static final int SPAWNS_PER_TICK = 2;
	/** A column that qualifies gets a mote with this chance times its weight. */
	private static final float ACCEPT = 0.6F;
	/** Sky light from which a spot counts as under the open sky (fireflies) rather than in a cave (spores). */
	private static final int OPEN_SKY = 8;
	/** Seconds a mote takes to fade in and out, and the shortest and longest life. */
	private static final float FADE = 2.0F;
	private static final float MIN_LIFE = 20.0F;
	private static final float MAX_LIFE = 60.0F;
	/** Motes closer to the camera than this fade out, gone at NEAR_GONE: a blur in front of the eyes. */
	private static final float NEAR_GONE = 0.6F;
	private static final float NEAR_FULL = 1.6F;
	/** Motes dimmer than this at their core are not drawn. */
	private static final float MIN_LIGHT = 0.002F;
	/** The core is never narrower than this sigma, in pixels: it stays steady as it moves across pixels. */
	private static final float MIN_CORE_PX = 1.1F;
	/** The halo's radius stays between these shares of the screen's height. */
	private static final float HALO_MIN_SCREEN = 0.01F;
	private static final float HALO_MAX_SCREEN = 0.05F;
	/** A surface this many blocks in front of a mote hides it completely, plus this much per block of distance. */
	private static final float HIDE_NEAR = 0.3F;
	private static final float HIDE_PER_BLOCK = 0.02F;
	/**
	 * A firefly's flash: its width as a share of the period (about 0.8 seconds over the brighter half, as real
	 * fireflies), and the faint glow it keeps between flashes, as a share of the flash.
	 */
	private static final double FLASH_WIDTH = 0.085;
	private static final double FIREFLY_GLOW = 0.2;
	/**
	 * Fireflies vary a little in colour, from this greener yellow-green (squared display colour) to the warmer one
	 * of Kind.FIREFLY.
	 */
	private static final float GREEN_RED = 0.48F;
	private static final float GREEN_BLUE = 0.24F;
	/** A gap between frames longer than this, in seconds, doesn't move the motes' clock further than this. */
	private static final float MAX_STEP = 0.1F;

	/**
	 * The kinds of mote: colour as squared display colour (about linear light), core sigma and halo radius in
	 * blocks, the halo's light at its centre as a share of the core's, and how bright the core gets at most.
	 */
	private enum Kind {
		FIREFLY(0.62F, 1.0F, 0.16F, 0.014F, 0.35F, 0.16F, 1.25F),
		PALE(0.70F, 0.74F, 0.82F, 0.012F, 0.28F, 0.10F, 0.55F),
		SPORE(0.55F, 0.85F, 0.20F, 0.010F, 0.28F, 0.12F, 0.5F),
		WISP(0.20F, 0.75F, 1.0F, 0.018F, 0.42F, 0.18F, 0.8F),
		END(0.70F, 0.35F, 1.0F, 0.016F, 0.42F, 0.16F, 0.7F);

		final float red;
		final float green;
		final float blue;
		final float core;
		final float halo;
		final float haloShare;
		final float light;

		Kind(float red, float green, float blue, float core, float halo, float haloShare, float light) {
			this.red = red;
			this.green = green;
			this.blue = blue;
			this.core = core;
			this.halo = halo;
			this.haloShare = haloShare;
			this.light = light;
		}
	}

	/** One mote: where it was placed (world coordinates), when, for how long, and its own phases. */
	private static final class Mote {
		final Kind kind;
		final double x;
		final double y;
		final double z;
		/** How far it moves up (spores, wisps) or down (pale motes) over its life, in blocks. */
		final double drift;
		final double birth;
		float life;
		/** Seconds between a firefly's flashes. */
		final float period;
		/** Its colour (squared display colour): the kind's, or between two for a firefly. */
		final float red;
		final float green;
		final float blue;
		/** Phases of its wandering and flashing, in radians or turns. */
		final double[] phase = new double[6];

		Mote(Kind kind, double x, double y, double z, double drift, double birth, float life, float period, SplittableRandom random) {
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.z = z;
			this.drift = drift;
			this.birth = birth;
			this.life = life;
			this.period = period;
			float tint = kind == Kind.FIREFLY ? random.nextFloat() : 0.0F;
			this.red = kind.red + (GREEN_RED - kind.red) * tint;
			this.green = kind.green;
			this.blue = kind.blue + (GREEN_BLUE - kind.blue) * tint;
			for (int i = 0; i < this.phase.length; i++) {
				this.phase[i] = random.nextDouble() * Math.PI * 2.0;
			}
		}
	}

	/** What a block is to the placing: what kind of ground or plant it is, and whether a mote can float in it. */
	private enum Ground { OTHER, GRASS, PLANT, BUSH, LUSH, SPORE_BLOSSOM, PALE, PALE_LEAVES, SOUL, END }

	private record BlockKind(Ground ground, boolean open) {
	}

	private static final BlockKind AIR = new BlockKind(Ground.OTHER, true);

	/** Each block's BlockKind, worked out the first time it is met. Render thread only. */
	private static final Map<Block, BlockKind> KINDS = new IdentityHashMap<>();

	private static final List<Mote> motes = new ArrayList<>();
	private static final SplittableRandom random = new SplittableRandom();
	private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private static final Matrix4f viewProjection = new Matrix4f();
	private static final Vector4f clip = new Vector4f();
	private static @Nullable ByteBuffer data;
	private static @Nullable GpuBuffer buffer;
	/** The motes' own clock in seconds: it stands still while the game is paused. */
	private static double clock;
	/** System.nanoTime of the last frame, 0 before the first. */
	private static long lastFrame;
	/** How many motes this frame draws: the Motes field of EtnaSettings. */
	private static int drawn;
	/** The level the motes were placed in, held weakly: a new one (another dimension, another world) starts afresh. */
	private static WeakReference<@Nullable ClientLevel> placedIn = new WeakReference<>(null);
	private static boolean broken;

	private EtnaMotes() {
	}

	/** Every client tick: motes that are done go, new ones are placed while there are fewer than wanted. */
	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		Entity camera = mc.getCameraEntity();
		int target = TARGET[EtnaConfig.fireflies.ordinal()];
		if (broken || !EtnaConfig.worldPassActive() || target == 0 || level == null || camera == null) {
			motes.clear();
			return;
		}
		try {
			if (level != placedIn.get()) {
				motes.clear();
				placedIn = new WeakReference<>(level);
			}
			Vec3 eye = camera.getEyePosition(1.0F);
			for (int i = motes.size() - 1; i >= 0; i--) {
				Mote mote = motes.get(i);
				float age = (float) (clock - mote.birth);
				if (age >= mote.life) {
					motes.remove(i);
					continue;
				}
				double dx = mote.x - eye.x;
				double dz = mote.z - eye.z;
				boolean left = dx * dx + dz * dz > LEAVE * LEAVE || Math.abs(mote.y - eye.y) > LEAVE;
				// Left behind, or its night is over (or the rain came): it fades out from here.
				if ((left || environment(mote.kind) <= 0.0F) && mote.life > age + FADE) {
					mote.life = age + FADE;
				}
			}
			if (motes.size() >= target) {
				return;
			}
			DimensionType.Skybox skybox = level.dimensionType().skybox();
			int placed = 0;
			for (int i = 0; i < SAMPLES_PER_TICK && placed < SPAWNS_PER_TICK && motes.size() < target; i++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				// Even over the area of the ring between MIN_RADIUS and RADIUS.
				double r = Math.sqrt(MIN_RADIUS * MIN_RADIUS + random.nextDouble() * (RADIUS * RADIUS - MIN_RADIUS * MIN_RADIUS));
				int x = (int) Math.floor(eye.x + Math.cos(angle) * r);
				int z = (int) Math.floor(eye.z + Math.sin(angle) * r);
				if (placeInColumn(level, skybox, x, (int) Math.floor(eye.y), z)) {
					placed++;
				}
			}
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/**
	 * Looks down one column for the first ground with open air or a plant over it, and places a mote there if it
	 * qualifies. Returns whether it placed one.
	 */
	private static boolean placeInColumn(ClientLevel level, DimensionType.Skybox skybox, int x, int eyeY, int z) {
		int top = eyeY + SCAN_UP;
		BlockKind above = kindOf(level.getBlockState(cursor.set(x, top + 1, z)));
		int paleLeaves = Integer.MIN_VALUE;
		boolean sporeBlossom = false;
		for (int y = top; y >= eyeY - SCAN_DOWN; y--) {
			BlockKind here = kindOf(level.getBlockState(cursor.set(x, y, z)));
			if (here.ground() == Ground.PALE_LEAVES && paleLeaves == Integer.MIN_VALUE) {
				paleLeaves = y;
			} else if (here.ground() == Ground.SPORE_BLOSSOM) {
				sporeBlossom = true;
			}
			if (!here.open() && above.open()) {
				return place(level, skybox, x, y, z, here.ground(), above.ground(), paleLeaves, sporeBlossom);
			}
			above = here;
		}
		return false;
	}

	/**
	 * The ground at (x, y, z) has air or a plant over it: decides what mote, if any, floats there. paleLeaves is the
	 * height of the first Pale Oak leaves met above it (Integer.MIN_VALUE: none), sporeBlossom whether a spore
	 * blossom hangs above it.
	 */
	private static boolean place(ClientLevel level, DimensionType.Skybox skybox, int x, int y, int z, Ground ground, Ground plant,
			int paleLeaves, boolean sporeBlossom) {
		double cx = x + 0.1 + 0.8 * random.nextDouble();
		double cz = z + 0.1 + 0.8 * random.nextDouble();
		double floor = y + 1.0;
		if (skybox == DimensionType.Skybox.END) {
			return (ground == Ground.END || plant == Ground.END)
				&& spawn(Kind.END, 1.0F, cx, floor + 0.5 + 2.5 * random.nextDouble(), cz, 0.0);
		}
		if (skybox != DimensionType.Skybox.OVERWORLD) {
			return ground == Ground.SOUL && spawn(Kind.WISP, 1.0F, cx, floor + 0.3 + 2.0 * random.nextDouble(), cz, 0.8);
		}
		int sky = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(cursor.set(x, y + 1, z));
		if (sky < OPEN_SKY) {
			boolean lush = ground == Ground.LUSH || plant == Ground.LUSH || sporeBlossom;
			return lush && spawn(Kind.SPORE, sporeBlossom ? 3.0F : 1.0F, cx, floor + 1.5 * random.nextDouble(), cz,
				1.5 + 1.5 * random.nextDouble());
		}
		if (paleLeaves != Integer.MIN_VALUE || ground == Ground.PALE || plant == Ground.PALE) {
			// From just under the canopy (or a few blocks up over open moss) down to a little over the ground.
			double start = paleLeaves != Integer.MIN_VALUE ? paleLeaves - 0.4 : floor + 2.0 + 3.0 * random.nextDouble();
			double end = floor + 0.2 + 0.4 * random.nextDouble();
			return start - end > 1.0 && spawn(Kind.PALE, 1.0F, cx, start, cz, end - start);
		}
		if (environment(Kind.FIREFLY) <= 0.0F) {
			return false;
		}
		float weight = plant == Ground.BUSH ? 3.0F
			: plant == Ground.PLANT || plant == Ground.LUSH ? 1.0F
			: plant == Ground.OTHER && (ground == Ground.GRASS || ground == Ground.LUSH) ? 0.3F : 0.0F;
		if (weight <= 0.0F) {
			return false;
		}
		if (nearWater(level, x, y, z)) {
			weight *= 2.5F;
		}
		return spawn(Kind.FIREFLY, weight, cx, floor + 0.3 + 1.3 * random.nextDouble(), cz, 0.0);
	}

	/** Water at the ground's level two blocks away on any side, or the ground itself water (a lily pad on it). */
	private static boolean nearWater(ClientLevel level, int x, int y, int z) {
		return water(level, x, y, z) || water(level, x + 2, y, z) || water(level, x - 2, y, z) || water(level, x, y, z + 2)
			|| water(level, x, y, z - 2);
	}

	private static boolean water(ClientLevel level, int x, int y, int z) {
		return level.getFluidState(cursor.set(x, y, z)).is(FluidTags.WATER);
	}

	/** Places a mote with a chance of ACCEPT times weight. */
	private static boolean spawn(Kind kind, float weight, double x, double y, double z, double drift) {
		if (random.nextFloat() >= weight * ACCEPT) {
			return false;
		}
		float life = MIN_LIFE + (MAX_LIFE - MIN_LIFE) * random.nextFloat();
		// Real fireflies of one kind flash on their own rhythm, about every five and a half seconds.
		float period = 4.0F + 3.0F * random.nextFloat();
		motes.add(new Mote(kind, x, y, z, drift, clock, life, period, random));
		return true;
	}

	/** How much a kind of mote shows right now, 0 .. 1: fireflies only from dusk to dawn and not in the rain. */
	private static float environment(Kind kind) {
		float sun = EtnaShadows.sunDirection().y;
		float dark = smoothstep(0.1F, -0.06F, sun);
		return switch (kind) {
			case FIREFLY -> dark * (1.0F - smoothstep(0.05F, 0.3F, EtnaShadows.rain()));
			case PALE -> 0.3F + 0.7F * dark;
			case SPORE, WISP, END -> 1.0F;
		};
	}

	/** What a block is to the placing, worked out once per block. */
	private static BlockKind kindOf(BlockState state) {
		if (state.isAir()) {
			return AIR;
		}
		return KINDS.computeIfAbsent(state.getBlock(), EtnaMotes::lookUp);
	}

	/** By the block's class (plants, leaves, hanging plants) and name, so mods' blocks named alike count too. */
	private static BlockKind lookUp(Block block) {
		String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (block instanceof LeavesBlock) {
			return new BlockKind(name.contains("pale_oak") ? Ground.PALE_LEAVES : Ground.OTHER, true);
		}
		if (name.contains("pale_moss") || block instanceof HangingMossBlock) {
			// Pale moss blocks are ground, its carpet and hanging moss something to float through.
			return new BlockKind(Ground.PALE, !name.equals("pale_moss_block"));
		}
		if (name.equals("spore_blossom")) {
			return new BlockKind(Ground.SPORE_BLOSSOM, true);
		}
		if (name.equals("moss_block") || name.equals("moss_carpet") || name.contains("azalea") || name.contains("dripleaf")
				|| block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock) {
			return new BlockKind(Ground.LUSH, !name.equals("moss_block"));
		}
		if (name.equals("firefly_bush")) {
			return new BlockKind(Ground.BUSH, true);
		}
		if (block instanceof VegetationBlock) {
			// Mushrooms, fungi and dead bushes are no place for fireflies, but a mote can still float over them.
			boolean bare = name.contains("mushroom") || name.contains("fungus") || name.contains("dead_bush") || name.contains("roots");
			return new BlockKind(bare ? Ground.OTHER : Ground.PLANT, true);
		}
		if (block instanceof HangingRootsBlock) {
			return new BlockKind(Ground.OTHER, true);
		}
		if (name.equals("grass_block") || name.equals("podzol")) {
			return new BlockKind(Ground.GRASS, false);
		}
		if (name.equals("soul_sand") || name.equals("soul_soil")) {
			return new BlockKind(Ground.SOUL, false);
		}
		if (name.equals("end_stone")) {
			return new BlockKind(Ground.END, false);
		}
		if (name.startsWith("chorus_")) {
			return new BlockKind(Ground.END, true);
		}
		return new BlockKind(Ground.OTHER, false);
	}

	/**
	 * Once per frame, after EtnaShadows.prepareFrame (it reads the sun and the rain) and before the settings block
	 * is written (EtnaLook.upload): moves the motes, projects them onto the screen and writes the block.
	 */
	public static void upload(LevelRenderState state) {
		long now = System.nanoTime();
		float dt = lastFrame == 0L ? 0.0F : Math.min((now - lastFrame) / 1.0e9F, MAX_STEP);
		lastFrame = now;
		drawn = 0;
		if (broken || motes.isEmpty()) {
			return;
		}
		if (!Minecraft.getInstance().isPaused()) {
			clock += dt;
		}
		GpuBuffer target = buffer;
		if (!EtnaConfig.worldPassActive() || target == null || target.isClosed()) {
			return;
		}
		try {
			ByteBuffer bytes = data;
			if (bytes == null) {
				bytes = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.nativeOrder());
				data = bytes;
			}
			for (int i = 0; i < TILES_X * TILES_Y; i++) {
				bytes.putInt(TILES_OFFSET + i * 4, 0);
			}
			RenderTarget screen = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			Vec3 camera = state.cameraRenderState.pos;
			EtnaFrame.viewProjection(viewProjection);
			for (Mote mote : motes) {
				if (drawn == MAX) {
					break;
				}
				if (project(bytes, mote, camera, screen.width, screen.height)) {
					drawn++;
				}
			}
			if (drawn > 0) {
				RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(), bytes);
			}
		} catch (RuntimeException e) {
			drawn = 0;
			fail(e);
		}
	}

	/** Where the mote is now, how bright, and where on screen: writes it as entry {@code drawn} if it shows. */
	private static boolean project(ByteBuffer bytes, Mote mote, Vec3 camera, int width, int height) {
		double t = clock;
		float age = (float) (t - mote.birth);
		float light = mote.kind.light * environment(mote.kind) * smoothstep(0.0F, FADE, age) * smoothstep(0.0F, FADE, mote.life - age);
		if (light < MIN_LIGHT) {
			return false;
		}
		double[] p = mote.phase;
		double life = Math.clamp(age / mote.life, 0.0, 1.0);
		double x = mote.x;
		double y = mote.y + mote.drift * life;
		double z = mote.z;
		switch (mote.kind) {
			case FIREFLY -> {
				// The flash: a smooth pulse under a second wide, once a period, over a faint glow; the firefly rises a
				// little through it.
				double turn = t / mote.period + p[0] / (Math.PI * 2.0);
				double f = (turn - Math.floor(turn) - 0.5) / FLASH_WIDTH;
				double flash = Math.exp(-f * f);
				light *= (float) (FIREFLY_GLOW + (1.0 - FIREFLY_GLOW) * flash);
				x += 0.55 * Math.sin(t * 0.31 + p[1]) + 0.25 * Math.sin(t * 0.73 + p[2]);
				z += 0.55 * Math.sin(t * 0.27 + p[3]) + 0.25 * Math.sin(t * 0.67 + p[4]);
				y += 0.25 * Math.sin(t * 0.41 + p[5]) + 0.15 * Math.exp(-f * f * 0.2);
			}
			case PALE, SPORE -> {
				light *= (float) (0.8 + 0.2 * Math.sin(t * 1.1 + p[0]));
				x += 0.35 * Math.sin(t * 0.53 + p[1]) + 0.12 * Math.sin(t * 1.3 + p[2]);
				z += 0.35 * Math.sin(t * 0.47 + p[3]) + 0.12 * Math.sin(t * 1.1 + p[4]);
			}
			case WISP -> {
				light *= (float) (0.65 + 0.35 * Math.sin(t * 0.7 + p[0]));
				x += 0.45 * Math.cos(t * 0.5 + p[1]);
				z += 0.45 * Math.sin(t * 0.5 + p[1]);
				y += 0.2 * Math.sin(t * 0.37 + p[2]);
			}
			case END -> {
				light *= (float) (0.7 + 0.3 * Math.sin(t * 0.6 + p[0]));
				x += 0.8 * Math.sin(t * 0.15 + p[1]);
				z += 0.8 * Math.sin(t * 0.13 + p[2]);
				y += 0.5 * Math.sin(t * 0.11 + p[3]);
			}
		}
		// Around the camera, in double precision, so the float that reaches the GPU is small and exact enough.
		float rx = (float) (x - camera.x);
		float ry = (float) (y - camera.y);
		float rz = (float) (z - camera.z);
		float distance = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
		light *= smoothstep(NEAR_GONE, NEAR_FULL, distance);
		if (light < MIN_LIGHT) {
			return false;
		}
		viewProjection.transform(clip.set(rx, ry, rz, 1.0F));
		if (clip.w < 0.05F) {
			return false;
		}
		float u = clip.x / clip.w;
		float v = clip.y / clip.w;
		// Pixels per block at the mote: a point a hundredth of its distance to the side, across the view.
		float sx = rz;
		float sz = -rx;
		float side = (float) Math.sqrt(sx * sx + sz * sz);
		if (side < 1.0e-4F) {
			// Straight above or below the camera: any horizontal direction is across the view.
			sx = 1.0F;
			sz = 0.0F;
			side = 1.0F;
		}
		float step = 0.01F * distance / side;
		viewProjection.transform(clip.set(rx + sx * step, ry, rz + sz * step, 1.0F));
		if (clip.w < 0.05F) {
			return false;
		}
		float du = (clip.x / clip.w - u) * width;
		float dv = (clip.y / clip.w - v) * height;
		float pixelsPerBlock = (float) Math.sqrt(du * du + dv * dv) / (0.01F * distance);

		Kind kind = mote.kind;
		float core = kind.core * pixelsPerBlock;
		float coreShare = 1.0F;
		if (core < MIN_CORE_PX) {
			coreShare = core / MIN_CORE_PX;
			core = MIN_CORE_PX;
		}
		float haloSize = kind.halo * pixelsPerBlock;
		float halo = Math.clamp(haloSize, HALO_MIN_SCREEN * height, HALO_MAX_SCREEN * height);
		// A halo widened to its smallest size on screen keeps its light: it gets fainter.
		float haloShare = kind.haloShare * Math.min(1.0F, haloSize / halo);
		float reachU = halo / width;
		float reachV = halo / height;
		if (u < -reachU || u > 1.0F + reachU || v < -reachV || v > 1.0F + reachV) {
			return false;
		}

		int i = drawn;
		float centre = light * coreShare;
		putVec4(bytes, POS_OFFSET + i * 16, u, v, distance, HIDE_NEAR + HIDE_PER_BLOCK * distance);
		putVec4(bytes, COLOR_OFFSET + i * 16, mote.red * centre, mote.green * centre, mote.blue * centre, haloShare / coreShare);
		putVec4(bytes, SHAPE_OFFSET + i * 16, 1.0F / (2.0F * core * core), 1.0F / (halo * halo), 0.0F, 0.0F);
		int x0 = Math.max(0, (int) Math.floor((u - reachU) * TILES_X));
		int x1 = Math.min(TILES_X - 1, (int) Math.floor((u + reachU) * TILES_X));
		int y0 = Math.max(0, (int) Math.floor((v - reachV) * TILES_Y));
		int y1 = Math.min(TILES_Y - 1, (int) Math.floor((v + reachV) * TILES_Y));
		for (int ty = y0; ty <= y1; ty++) {
			for (int tx = x0; tx <= x1; tx++) {
				int at = TILES_OFFSET + (ty * TILES_X + tx) * 4;
				bytes.putInt(at, bytes.getInt(at) | 1 << i);
			}
		}
		return true;
	}

	private static void putVec4(ByteBuffer bytes, int at, float x, float y, float z, float w) {
		bytes.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
	}

	/**
	 * The block's buffer, for the last world pass (PostPassMixin): made on first use and kept for the session, as
	 * the passes of every chain loaded later share it. The pass reads it only while writePost says there are motes.
	 */
	public static GpuBuffer postBlock() {
		GpuBuffer b = buffer;
		if (b == null || b.isClosed()) {
			b = RenderSystem.getDevice().createBuffer(() -> "Etna / " + BLOCK, EtnaLook.USAGE, SIZE);
			buffer = b;
		}
		return b;
	}

	/**
	 * The Motes vec4 of the post passes' EtnaSettings block, bytes 496 to 511: x how many motes this frame draws (0:
	 * the last world pass skips them), yzw 0.
	 */
	public static void writePost(Std140Builder builder) {
		builder.putVec4(new Vector4f(drawn, 0.0F, 0.0F, 0.0F));
	}

	private static void fail(RuntimeException e) {
		broken = true;
		motes.clear();
		drawn = 0;
		EtnaClient.LOGGER.error("Etna: the fireflies failed, they are off for this session", e);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
