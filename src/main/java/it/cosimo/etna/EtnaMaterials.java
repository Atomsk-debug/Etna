package it.cosimo.etna;

import java.util.function.Predicate;

import com.mojang.blaze3d.buffers.Std140Builder;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Materials by block type, for terrain_surface.glsl: metals mirror the sky tinted by their own colour, gems
 * and ore specks glint, polished stone, glass and ice take a reflection of the sky, snow, sand and ice
 * sparkle, and the bright specks of redstone, amethyst, sculk and the like glow softly. The shader knows a
 * block's material by the same tag EtnaFoliage gives leaves and plants: the alpha of its vertex colour, here
 * TAG_MATERIAL + the material's class (terrain.vsh). It works from the block's own texture, so it follows any
 * resource pack at any resolution, and the materials go by the blocks' names, so blocks that mods name the
 * same way (a tin ore, a mod's polished stone) get them too.
 *
 * The tags go in as chunks are built, through a wrapper round the models of those blocks (EtnaFoliage
 * registers it, from the same model loading hook as its own), and only while Etna is on, as EtnaFoliage's:
 * with Etna off the chunks are built again just as vanilla builds them. The class is worked out once per block
 * state as models are baked; building a chunk costs a transform per quad that sets four alphas.
 */
public final class EtnaMaterials {
	/** A material's tag is this plus its class, out of 255: below EtnaFoliage's plants (220), which never reach it. */
	private static final int TAG_MATERIAL = 200;

	// The classes, as terrain_surface.glsl numbers them (SURF_*).
	private static final int METAL = 1;
	private static final int GEM = 2;
	private static final int POLISHED = 3;
	private static final int ORE = 4;
	private static final int GLASS = 5;
	private static final int ICE = 6;
	private static final int SNOW = 7;
	private static final int SAND = 8;
	private static final int GLOWING = 9;
	private static final int CLASSES = 10;

	/** The tagging transform of each class, by class; 0 has none. */
	private static final QuadTransform[] TAGS = new QuadTransform[CLASSES];

	static {
		for (int material = 1; material < CLASSES; material++) {
			int alpha = (TAG_MATERIAL + material) << 24;
			TAGS[material] = quad -> {
				for (int i = 0; i < 4; i++) {
					quad.color(i, quad.color(i) & 0x00FFFFFF | alpha);
				}
				return true;
			};
		}
	}

	private EtnaMaterials() {
	}

	/** The model a block's state is drawn with: wrapped to put in its material's tag, or as it is for blocks without one. */
	static BlockStateModel wrap(BlockStateModel model, Block block) {
		int material = materialOf(BuiltInRegistries.BLOCK.getKey(block).getPath());
		return material == 0 ? model : new Tagged(model, material);
	}

	/**
	 * A block's material class by its name, 0 for none. Glowing specks come first (redstone ore is an ore that
	 * glows), then the rest. Copper counts as metal in every state of weathering: the shader tells its green
	 * patina from bare metal by colour, texel by texel.
	 */
	static int materialOf(String name) {
		if (name.equals("redstone_ore") || name.equals("deepslate_redstone_ore") || name.equals("redstone_block")
				|| name.equals("crying_obsidian") || name.equals("amethyst_cluster") || name.endsWith("amethyst_bud")
				|| name.equals("glow_lichen") || name.contains("sculk") || name.equals("magma_block")) {
			return GLOWING;
		}
		if (name.equals("gold_block") || name.equals("netherite_block") || name.startsWith("iron_") && !name.endsWith("_ore")
				|| name.equals("chain") || name.endsWith("anvil") || name.equals("cauldron") || name.equals("hopper")
				|| name.equals("bell") || name.endsWith("lightning_rod") || name.equals("heavy_core")
				|| name.endsWith("weighted_pressure_plate")
				|| name.endsWith("lantern") && !name.equals("sea_lantern") && !name.equals("jack_o_lantern")
				|| name.contains("copper") && !name.endsWith("_ore") && !name.startsWith("raw_") && !name.contains("torch")
					&& !name.contains("golem") && !name.contains("chest")) {
			return METAL;
		}
		if (name.equals("diamond_block") || name.equals("emerald_block") || name.equals("lapis_block")
				|| name.equals("amethyst_block") || name.equals("budding_amethyst")) {
			return GEM;
		}
		if (name.endsWith("_ore") || name.equals("ancient_debris") || name.equals("gilded_blackstone")
				|| name.startsWith("raw_") && name.endsWith("_block")) {
			return ORE;
		}
		if (name.contains("polished") || name.startsWith("smooth_") || name.contains("quartz")
				|| name.endsWith("glazed_terracotta") || name.equals("obsidian")) {
			return POLISHED;
		}
		if (name.contains("glass")) {
			return GLASS;
		}
		if (name.equals("ice") || name.equals("packed_ice") || name.equals("blue_ice") || name.equals("frosted_ice")) {
			return ICE;
		}
		if (name.equals("snow") || name.equals("snow_block") || name.equals("powder_snow")) {
			return SNOW;
		}
		if (name.equals("sand") || name.equals("red_sand") || name.equals("suspicious_sand")) {
			return SAND;
		}
		return 0;
	}

	/**
	 * The EtnaSurface and EtnaSurface2 vec4s of the terrain block, bytes 512 to 543: the materials, puddles,
	 * sparkles and glowing flowers settings, and how much the specks of glowing blocks glow (the glowing blocks
	 * setting: unlike the light-15 glow of terrain_dark.glsl, the specks don't need smooth lighting), and the share of
	 * the screen's width the level is drawn at this frame (EtnaScale), which the block textures' detail follows,
	 * and this frame's anti-aliasing jitter in the level's pixels (EtnaFrame; 0 without anti-aliasing), which the
	 * water's glints are held still against (terrain_water.glsl, etnaUnjitter).
	 */
	public static void writeTerrain(Std140Builder builder) {
		builder.putVec4(new Vector4f(EtnaConfig.materials.value, EtnaConfig.puddles.value, EtnaConfig.sparkles.value,
				EtnaConfig.glowingFlowers.value))
			.putVec4(new Vector4f(EtnaConfig.emission.value, (float) EtnaScale.levelWidth() / EtnaScale.screenWidth(),
				EtnaFrame.jitterX(), EtnaFrame.jitterY()));
	}

	/** A block's model that puts in its material's tag while Etna is on. */
	private static final class Tagged extends WrapperBlockStateModel {
		private final int material;

		Tagged(BlockStateModel wrapped, int material) {
			super(wrapped);
			this.material = material;
		}

		@Override
		public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
				Predicate<@Nullable Direction> cullTest) {
			if (!EtnaFoliage.marking()) {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
				return;
			}
			emitter.pushTransform(TAGS[this.material]);
			try {
				super.emitQuads(emitter, level, pos, state, random, cullTest);
			} finally {
				emitter.popTransform();
			}
		}

		@Override
		public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
			// The tag is the same wherever the block is, so tagged geometry can still be cached, under a key of its
			// own: the same key with Etna off stands for quads without it.
			Object key = super.createGeometryKey(level, pos, state, random);
			return key == null || !EtnaFoliage.marking() ? key : new Key(key, this.material);
		}
	}

	/** The geometry key of a tagged model: the wrapped model's, and the material it carries. */
	private record Key(Object model, int material) {
	}
}
