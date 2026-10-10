package it.cosimo.etna;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.CoralFanBlock;
import net.minecraft.world.level.block.CoralPlantBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LilyPadBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

/**
 * The tags terrain.vsh knows leaves and plants by, so they wave in the wind: the alpha of their vertex colour,
 * which vanilla always leaves at 255 in chunks (terrain.vsh puts it back to 1 before anything uses it). Leaves
 * carry TAG_LEAVES on every vertex. A plant's vertices carry how far they sway, 0 at its roots to 1 where it
 * is free, in sixteenths (TAG_PLANT + 0 .. 16, or TAG_PLANT_SHADED + 0 .. 16 on faces vanilla shades by the
 * way they face): their height in the block's own model, from where the plant grows (the ground, or the
 * ceiling for vines and roots that hang), so a flower bends at its foot. Where a plant carries on into the
 * next block of a plant with tags (tall grass, sugar cane, kelp, vines), that edge sways fully in both, so
 * they move in one piece; where it hangs from leaves, that edge sways as much as the leaves do.
 * The wave itself only depends on a vertex's place in the world and its tag, so vertices that meet move alike
 * and no gap opens, whatever the model's shape. The shape itself and the light are vanilla's.
 * The tags go in as chunks are built, by a wrapper round the leaf and plant models (register), and only while
 * Etna is on: with Etna off the chunks are built again just as vanilla builds them. Until 0.7.1 leaves and
 * plants were told by their vertices lying a little off the block grid, through Etna's copies of vanilla's
 * leaves and cross models: that changed the game with Etna off, was lost under a resource pack with models of
 * its own, and missed most short grass and ferns, which the game moves up and down a little.
 */
public final class EtnaFoliage {
	/** The tag of leaves, out of 255. */
	private static final int TAG_LEAVES = 254;
	/**
	 * The tag of a plant's vertex that stays still, on a face vanilla shades as if it faced up (crosses and
	 * crops, whose faces have "shade": false, and tops); TAG_PLANT + 16 sways fully.
	 */
	private static final int TAG_PLANT = 237;
	/** The same on a face vanilla shades darker, by the way it faces (the sides of pitcher plants and dripleaves). */
	private static final int TAG_PLANT_SHADED = 220;
	/**
	 * How far leaves sway, in sixteenths of a plant's free end (terrain.vsh moves leaves by the same amount):
	 * the edge of a plant hanging from leaves sways this much, so it stays on them.
	 */
	private static final int LEAF_SWAY = 7;

	/** Plant models lower than this (petals, leaf litter, lily pads: flat on the ground or the water) stay still. */
	private static final float MIN_PLANT_HEIGHT = 0.3F;

	/** Whether chunks are built with the tags: Etna on, or as they were while a benchmark runs (update). */
	private static volatile boolean marking = true;

	private EtnaFoliage() {
	}

	/**
	 * Every client tick: when Etna is turned on or off, the chunks around are built again, with or without the
	 * tags. A benchmark run keeps them as they are: it turns Etna off and on to measure, and building every
	 * chunk again would be measured with it. With vanilla's shader the tags don't show (its alpha test lets an
	 * alpha of 220 out of 255 through).
	 */
	public static void update(Minecraft mc) {
		boolean want = EtnaBenchmark.running() ? marking : EtnaConfig.enabled;
		if (want != marking) {
			marking = want;
			rebuildAround(mc);
		}
	}

	/**
	 * Every section within the render distance built again, as a block change would: each keeps its old mesh
	 * on screen until the new one is ready, so the world doesn't blink as vanilla's full reload does.
	 */
	private static void rebuildAround(Minecraft mc) {
		ClientLevel level = mc.level;
		Entity camera = mc.getCameraEntity();
		if (level == null || camera == null) {
			return;
		}
		BlockPos at = camera.blockPosition();
		int x = SectionPos.blockToSectionCoord(at.getX());
		int z = SectionPos.blockToSectionCoord(at.getZ());
		int radius = mc.options.getEffectiveRenderDistance() + 1;
		level.setSectionRangeDirty(x - radius, level.getMinSectionY(), z - radius, x + radius, level.getMaxSectionY(), z + radius);
	}

	/**
	 * Registers the model wrapper: after the game bakes a block's models, those of leaves and plants are wrapped
	 * so the tags go in as Fabric's renderer emits their quads for a chunk (Fabric API draws the blocks of every
	 * chunk through it, Indigo by default). The wrapper's transform runs first, on the model's own quads in the
	 * block's space, before the renderer tints, shades and moves them into place. Models are baked again with
	 * each resource reload, so the plant states are gathered anew each time, into sets of that load's own:
	 * chunks still being built with the old models keep reading the old sets.
	 */
	public static void register() {
		marking = EtnaConfig.enabled;
		ModelLoadingPlugin.register(context -> {
			// The block states of the plants with tags, by where they grow from: a plant carries on into these.
			Set<BlockState> rising = ConcurrentHashMap.newKeySet();
			Set<BlockState> hanging = ConcurrentHashMap.newKeySet();
			context.modifyBlockModelAfterBake().register((model, bake) -> {
				BlockState state = bake.state();
				Block block = state.getBlock();
				if (block instanceof LeavesBlock) {
					return new Leaves(model);
				}
				if (!isPlant(block)) {
					return model;
				}
				boolean hangs = hangs(block)
					|| state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING);
				if (reach(model, hangs) < MIN_PLANT_HEIGHT) {
					return model;
				}
				Set<BlockState> tagged = hangs ? hanging : rising;
				tagged.add(state);
				return new Plant(model, block, hangs, tagged);
			});
		});
	}

	/**
	 * Plants that sway: flowers, grass, ferns, crops, saplings, mushrooms, sugar cane, kelp, vines, roots, moss
	 * and living corals. Not those that lie on the water, sit like a pebble or make a bush to stand on, nor a
	 * stem grown into its melon or pumpkin (it would pull out of the fruit); dead corals and corals on walls
	 * are not plants here.
	 */
	private static boolean isPlant(Block block) {
		if (block instanceof LilyPadBlock || block instanceof SeaPickleBlock || block instanceof AzaleaBlock
				|| block instanceof AttachedStemBlock) {
			return false;
		}
		return block instanceof VegetationBlock || block instanceof SugarCaneBlock || block instanceof GrowingPlantBlock
			|| block instanceof BambooSaplingBlock || block instanceof HangingRootsBlock || block instanceof HangingMossBlock
			|| block instanceof CoralPlantBlock || block instanceof CoralFanBlock;
	}

	/** Plants that hang from the ceiling, so grow from the top of their block (and mangrove propagules hanging from leaves). */
	private static boolean hangs(Block block) {
		return block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock || block instanceof CaveVinesBlock
			|| block instanceof CaveVinesPlantBlock || block instanceof HangingRootsBlock || block instanceof HangingMossBlock;
	}

	/**
	 * How far the model reaches into its block from where the plant grows (up from the bottom, or down from the
	 * top for one that hangs), over a few of its random variants.
	 */
	private static float reach(BlockStateModel model, boolean hangs) {
		float reach = 0.0F;
		List<BlockStateModelPart> parts = new ArrayList<>();
		for (long seed = 0; seed < 4; seed++) {
			parts.clear();
			model.collectParts(RandomSource.create(seed), parts);
			for (BlockStateModelPart part : parts) {
				for (Direction side : Direction.values()) {
					reach = Math.max(reach, reach(part.getQuads(side), hangs));
				}
				reach = Math.max(reach, reach(part.getQuads(null), hangs));
			}
		}
		return reach;
	}

	private static float reach(List<BakedQuad> quads, boolean hangs) {
		float reach = 0.0F;
		for (BakedQuad quad : quads) {
			for (int i = 0; i < 4; i++) {
				float y = quad.position(i).y();
				reach = Math.max(reach, hangs ? 1.0F - y : y);
			}
		}
		return reach;
	}

	private static void tag(MutableQuadView quad, int vertex, int tag) {
		quad.color(vertex, quad.color(vertex) & 0x00FFFFFF | tag << 24);
	}

	private static final QuadTransform LEAVES = quad -> {
		for (int i = 0; i < 4; i++) {
			tag(quad, i, TAG_LEAVES);
		}
		return true;
	};

	/**
	 * A plant's tags, for how far it sways at its root end (the edge it grows from) and at its other end, in
	 * sixteenths, and in between by the vertex's height from the root end. The renderer shades a face as if it
	 * faced its shade direction, or its own way when it has none (Indigo's AoCalculator and FlatLighter): a
	 * face shaded as anything but up gets TAG_PLANT_SHADED, so terrain.fsh undoes that as it does for blocks.
	 */
	private static QuadTransform plantTags(boolean hangs, int atRoot, int atTip) {
		return quad -> {
			Direction shade = quad.shadeDirectionOverride();
			int base = (shade != null ? shade : quad.lightFace()) == Direction.UP ? TAG_PLANT : TAG_PLANT_SHADED;
			for (int i = 0; i < 4; i++) {
				float up = Math.clamp(quad.y(i), 0.0F, 1.0F);
				float sway = atRoot + (atTip - atRoot) * (hangs ? 1.0F - up : up);
				tag(quad, i, base + Math.round(sway));
			}
			return true;
		};
	}

	/** The root end's sway: still, as much as leaves, or fully. */
	private static final int[] ROOT_SWAY = {0, LEAF_SWAY, 16};
	private static final int ROOT_STILL = 0;
	private static final int ROOT_LEAVES = 1;
	private static final int ROOT_FREE = 2;

	/** By hangs (0 grows up, 1 hangs), the root end's sway (ROOT_*), then the free end swaying or not. */
	private static final QuadTransform[][][] PLANT_TAGS = new QuadTransform[2][ROOT_SWAY.length][2];

	static {
		for (int hangs = 0; hangs < 2; hangs++) {
			for (int root = 0; root < ROOT_SWAY.length; root++) {
				for (int tip = 0; tip < 2; tip++) {
					PLANT_TAGS[hangs][root][tip] = plantTags(hangs == 1, ROOT_SWAY[root], tip * 16);
				}
			}
		}
	}

	/** A leaf or plant model that puts in the tags while Etna is on. */
	private abstract static class Marked extends WrapperBlockStateModel {
		Marked(BlockStateModel wrapped) {
			super(wrapped);
		}

		/** The tags for this block where it is. */
		abstract QuadTransform tags(BlockAndTintGetter level, BlockPos pos);

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
				Predicate<@Nullable Direction> cullTest) {
			if (!marking) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			emitter.pushTransform(tags(level, pos));
			try {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
			} finally {
				emitter.popTransform();
			}
		}

		@Override
		public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
			// Tagged quads differ from what the same key stands for with Etna off, and a plant's from one place
			// to another: no key, nothing cached.
			return marking ? null : super.createGeometryKey(level, pos, state, random);
		}
	}

	private static final class Leaves extends Marked {
		Leaves(BlockStateModel wrapped) {
			super(wrapped);
		}

		@Override
		QuadTransform tags(BlockAndTintGetter level, BlockPos pos) {
			return LEAVES;
		}
	}

	/**
	 * A plant sways at its free end, and fully where it carries on into the next block of a plant with tags,
	 * which moves with it; where it grows from leaves, that end sways as the leaves do. Where it carries on into
	 * a block of the same plant without tags (a resource pack's flat top, say), that end stays still, as that
	 * block does.
	 */
	private static final class Plant extends Marked {
		private final Block block;
		private final boolean hangs;
		/** The states of the plants with tags that grow the same way as this one, from the same model load. */
		private final Set<BlockState> tagged;

		Plant(BlockStateModel wrapped, Block block, boolean hangs, Set<BlockState> tagged) {
			super(wrapped);
			this.block = block;
			this.hangs = hangs;
			this.tagged = tagged;
		}

		@Override
		QuadTransform tags(BlockAndTintGetter level, BlockPos pos) {
			BlockState root = level.getBlockState(hangs ? pos.above() : pos.below());
			BlockState tip = level.getBlockState(hangs ? pos.below() : pos.above());
			int rootSway = tagged.contains(root) ? ROOT_FREE : root.getBlock() instanceof LeavesBlock ? ROOT_LEAVES : ROOT_STILL;
			boolean tipSways = !continues(tip.getBlock()) || tagged.contains(tip);
			return PLANT_TAGS[hangs ? 1 : 0][rootSway][tipSways ? 1 : 0];
		}

		/** Whether a block next to this one is more of the same plant: its other half, or the next piece of a vine or kelp. */
		private boolean continues(Block next) {
			return next == block || block instanceof GrowingPlantBlock && next instanceof GrowingPlantBlock;
		}
	}
}
