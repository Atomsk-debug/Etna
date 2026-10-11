package it.cosimo.etna;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Mobs, block entities, armour, items and the first-person hand lit like the terrain (0.9.0): the golden sun, the
 * blue shade of the sky, the shadow map, the clouds' shadows and the warm torch colour, instead of vanilla's two
 * fixed lights over its lightmap, which left them looking pasted onto Etna's land.
 *
 * Every entity, block entity and item draw, in the world, in the hand and in the menus alike, goes through one
 * private method of vanilla's prepared render types, which sets the draw's pipeline on its render pass and then
 * binds the default uniforms. PreparedRenderTypeMixin swaps that pipeline for Etna's copy of it (pipelineFor) and,
 * when it did, binds Etna's terrain block, shadow maps and cloud texture on the pass along with the default
 * uniforms (bind), as the shadow-receiving terrain has them (EtnaShadows.bindLight). The copies are vanilla's
 * pipelines with Etna's entity or item shader (assets/etna/shaders/core/entity.* and item.*) and Etna's bind group
 * added: the same vertex format, blending, depth test, culling and uniform names, so the draw and its routing stay
 * vanilla's.
 *
 * Only draws Etna should light are swapped: while Etna's terrain block is ready this frame (EtnaShadows.lightBlock,
 * so Etna is on and in a world), Entity Lighting is on, and the draw is part of the level (from the shadow pass
 * hook in LevelRendererMixin until the hand) or of the first-person hand (GameRendererMixin, from right before the
 * hand is drawn until it is drawn), never the menus or the screen effects: the inventory's items and mobs, the item atlas and
 * picture-in-picture use their own projection and lights, and draw after the level. The hand's copies define
 * ETNA_HAND: it is drawn close to the eye with its own projection, so it is lit as if it stood at the camera.
 * Only the pipelines in pipelines() are swapped, by identity (the glint variants share their locations with the
 * plain ones): the classic ones. Improved Transparency's stage pipelines, emissive parts, eyes, the energy swirl,
 * the breeze's wind, the end crystal's beam, the dragon's dissolve, shadows, outlines, the separate glint pass and
 * block cracks stay vanilla's.
 *
 * Both injections are optional (require = 0): if the game changes under them, entities simply keep vanilla's light.
 * Nothing is swapped until the binding injection has run once, so a copy is never drawn without its block; if
 * neither ever runs while Etna would light entities, that is logged once. The copies are compiled a couple a frame
 * as the level starts (warmUp), not at their first draw. A copy that does not compile leaves its pipeline
 * vanilla's there (level or hand) until a resource reload, an error leaves all of them vanilla's.
 */
public final class EtnaEntities {
	private static final Identifier ENTITY_SHADER = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "core/entity");
	private static final Identifier ITEM_SHADER = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "core/item");
	/** Frames of the level drawn with Entity Lighting active before a hook that never ran is logged. */
	private static final int HOOK_GRACE_FRAMES = 600;
	/** Copies built and compiled ahead of their first draw, per frame of the level (warmUp). */
	private static final int WARM_UP_PER_FRAME = 2;

	/** Which part of the frame is being drawn, for which copies, if any, to draw with. */
	private enum Phase { NONE, WORLD, HAND }

	private static Phase phase = Phase.NONE;
	/** The vanilla pipelines Etna lights, each with the shader of its copy; made on first use (pipelines). */
	private static @Nullable Map<RenderPipeline, Identifier> lit;
	/** The same pipelines in a fixed order, for warmUp. */
	private static RenderPipeline @Nullable [] litOrder;
	/** Copies that compiled, by the vanilla pipeline they stand in for: until the next resource reload. */
	private static final Map<RenderPipeline, RenderPipeline> worldCopies = new IdentityHashMap<>();
	private static final Map<RenderPipeline, RenderPipeline> handCopies = new IdentityHashMap<>();
	/** Vanilla pipelines whose copy did not compile, for the level and for the hand: drawn as vanilla's until the next resource reload. */
	private static final Set<RenderPipeline> failedWorld = new ReferenceOpenHashSet<>();
	private static final Set<RenderPipeline> failedHand = new ReferenceOpenHashSet<>();
	/** How many copies warmUp has prepared since the last resource reload, world and hand ones in turn. */
	private static int warmed;
	/** Set after an error: vanilla's light on every entity for the rest of the session. */
	private static boolean broken;
	/** Whether each injection has run at least once: the pipeline swap, and the binding after it. */
	private static boolean swapHookSeen;
	private static boolean bindHookSeen;
	/** Whether the draw now going on had its pipeline swapped, so bind gives it Etna's block. */
	private static boolean swapped;
	private static int framesWithoutHooks;
	private static boolean hooksWarned;

	private EtnaEntities() {
	}

	/**
	 * LevelRendererMixin, at the shadow pass hook, before the level's draws: the draws from here to the hand belong to
	 * the level. Also notices, once, when the injections never run.
	 */
	public static void beginWorld() {
		phase = Phase.WORLD;
		warmUp();
		if (!hooksWarned && !(swapHookSeen && bindHookSeen) && wanted() && ++framesWithoutHooks > HOOK_GRACE_FRAMES) {
			hooksWarned = true;
			EtnaClient.LOGGER.warn("Etna: the entity lighting hook did not run (pipeline swap {}, binding {}); entities, items and the hand"
				+ " keep vanilla's light. A newer Minecraft may have changed the code it needs.", swapHookSeen, bindHookSeen);
		}
	}

	/** GameRendererMixin, right before the hand is drawn: the draws from here to the end of renderLevel are the hand's. */
	public static void beginHand() {
		phase = Phase.HAND;
	}

	/**
	 * GameRendererMixin, at the end of renderLevel, at the start of every frame and right after the hand is drawn
	 * (before the screen effects, whose totem is lit as vanilla's): the menus and anything else are vanilla's.
	 */
	public static void endLevel() {
		phase = Phase.NONE;
	}

	/** Whether Etna lights entities this frame at all: Etna on in a world, its block ready, the setting on. */
	private static boolean wanted() {
		return EtnaConfig.entityLighting && !broken && EtnaClient.activeEffect() != null && EtnaShadows.lightBlock() != null;
	}

	/**
	 * PreparedRenderTypeMixin, at the start of a draw: the pipeline to draw with, Etna's copy of {@code vanilla} while
	 * Etna lights this draw, else {@code vanilla} itself.
	 */
	public static RenderPipeline pipelineFor(RenderPipeline vanilla) {
		swapHookSeen = true;
		swapped = false;
		if (phase == Phase.NONE || !bindHookSeen || !wanted()) {
			return vanilla;
		}
		boolean hand = phase == Phase.HAND;
		RenderPipeline copy = (hand ? handCopies : worldCopies).get(vanilla);
		if (copy == null) {
			copy = prepare(vanilla, hand);
			if (copy == null) {
				return vanilla;
			}
		}
		swapped = true;
		return copy;
	}

	/**
	 * The copy of {@code vanilla} for the level or the hand, built and compiled now and kept until the next resource
	 * reload; null if {@code vanilla} is not one Etna lights, its copy did not compile, or an error left entities
	 * vanilla's. The compile is checked once here, not at every draw.
	 */
	private static @Nullable RenderPipeline prepare(RenderPipeline vanilla, boolean hand) {
		Set<RenderPipeline> failed = hand ? failedHand : failedWorld;
		if (broken || failed.contains(vanilla)) {
			return null;
		}
		try {
			Identifier shader = pipelines().get(vanilla);
			if (shader == null) {
				return null;
			}
			RenderPipeline copy = makeCopy(vanilla, shader, hand);
			if (RenderSystem.getCompiledPipelineNullable(copy) == null) {
				failed.add(vanilla);
				EtnaClient.LOGGER.error("Etna: {} did not compile, those entities keep vanilla's light", copy.getLocation());
				return null;
			}
			(hand ? handCopies : worldCopies).put(vanilla, copy);
			return copy;
		} catch (RuntimeException e) {
			broken = true;
			EtnaClient.LOGGER.error("Etna: lighting entities failed, entities, items and the hand keep vanilla's light for this session", e);
			return null;
		}
	}

	/**
	 * At the start of the level, a couple of copies a frame are compiled before anything draws with them, all of
	 * them over the first frames in a world and after a resource reload. Compiled at their first draw instead, each
	 * would stall the game when a new kind of mob, an enchanted item or the hand first came into view, which on some
	 * drivers takes a good part of a second.
	 */
	private static void warmUp() {
		if (!wanted()) {
			return;
		}
		pipelines();
		RenderPipeline[] order = litOrder;
		for (int i = 0; i < WARM_UP_PER_FRAME && order != null && warmed < 2 * order.length; i++, warmed++) {
			RenderPipeline vanilla = order[warmed >> 1];
			boolean hand = (warmed & 1) == 1;
			if (!(hand ? handCopies : worldCopies).containsKey(vanilla)) {
				prepare(vanilla, hand);
			}
		}
	}

	/**
	 * PreparedRenderTypeMixin, as the draw binds its default uniforms, after its pipeline is set: Etna's block, shadow
	 * maps and cloud texture for a copy (pipelineFor). They stay bound for the rest of the pass, where pipelines
	 * without them leave them unread.
	 */
	public static void bind(RenderPass pass) {
		bindHookSeen = true;
		if (!swapped) {
			return;
		}
		swapped = false;
		if (EtnaShadows.lightBlock() != null) {
			EtnaShadows.bindLight(pass);
		}
	}

	/**
	 * The vanilla pipelines Etna lights, by identity, with the shader their copies draw with. The classic ones only:
	 * every entity, block entity and armour pipeline lit by vanilla's lightmap, and the item pipelines.
	 */
	private static Map<RenderPipeline, Identifier> pipelines() {
		Map<RenderPipeline, Identifier> table = lit;
		if (table == null) {
			table = new IdentityHashMap<>();
			for (RenderPipeline p : new RenderPipeline[] {
				RenderPipelines.ENTITY_SOLID,
				// Item frames.
				RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
				// Chests.
				RenderPipelines.ENTITY_CUTOUT_CULL,
				RenderPipelines.ENTITY_CUTOUT,
				RenderPipelines.ENTITY_CUTOUT_Z_OFFSET,
				// The arm, and translucent mobs.
				RenderPipelines.ENTITY_TRANSLUCENT,
				RenderPipelines.ENTITY_TRANSLUCENT_CULL,
				RenderPipelines.ARMOR_CUTOUT_NO_CULL,
				RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL,
				RenderPipelines.WOLF_ARMOR_CRACKS,
				RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT,
				// Tridents and shields.
				RenderPipelines.ENTITY_SOLID_GLINT,
				RenderPipelines.BANNER_PATTERN
			}) {
				table.put(p, ENTITY_SHADER);
			}
			for (RenderPipeline p : new RenderPipeline[] {
				RenderPipelines.ITEM_CUTOUT,
				RenderPipelines.ITEM_CUTOUT_GLINT,
				RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL,
				RenderPipelines.ITEM_TRANSLUCENT,
				RenderPipelines.ITEM_TRANSLUCENT_GLINT,
				RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL
			}) {
				table.put(p, ITEM_SHADER);
			}
			litOrder = table.keySet().toArray(new RenderPipeline[0]);
			lit = table;
		}
		return table;
	}

	/**
	 * Etna's copy of a vanilla entity or item pipeline: everything of vanilla's, drawn with Etna's shader, with Etna's
	 * block (ETNA_SHADOWS) and its bind group (EtnaShadows.LAYOUT), for the level or for the hand (ETNA_HAND).
	 */
	private static RenderPipeline makeCopy(RenderPipeline vanilla, Identifier shader, boolean hand) {
		RenderPipeline.Builder builder = EtnaShadows.copy(vanilla, true)
			.withVertexShader(shader)
			.withFragmentShader(shader)
			.withShaderDefine("ETNA_SHADOWS");
		if (!EtnaConfig.shadowGather) {
			// shadowGather=false in the file: the shadow filter reads one texel at a time, as on the terrain.
			builder.withShaderDefine("ETNA_SHADOW_FETCH");
		}
		if (hand) {
			builder.withShaderDefine("ETNA_HAND");
		}
		// The glint variants share their locations with the plain pipelines (or the special glint with the plain
		// glint): their copies get names of their own.
		String path = vanilla.getLocation().getPath();
		var flags = vanilla.getShaderDefines().flags();
		if (flags.contains("GLINT_SPECIAL")) {
			path += path.contains("glint") ? "_special" : "_glint_special";
		} else if (flags.contains("GLINT") && !path.contains("glint")) {
			path += "_glint";
		}
		return builder
			.withBindGroupLayout(EtnaShadows.LAYOUT)
			.withLocation(Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, (hand ? "hand/" : "lit/") + path))
			.build();
	}

	/**
	 * EtnaFrame.resourcesReloaded: the shaders were recompiled, so every copy is built and checked again, and one
	 * that failed gets another try.
	 */
	static void resourcesReloaded() {
		worldCopies.clear();
		handCopies.clear();
		failedWorld.clear();
		failedHand.clear();
		warmed = 0;
	}
}
