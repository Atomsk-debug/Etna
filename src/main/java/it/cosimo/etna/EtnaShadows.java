package it.cosimo.etna;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Real cast shadows: a shadow map rendered from the sun (or the moon) every frame.
 *
 * There are two maps. The map's direction holds still while the sun creeps on, and when the sun has moved
 * far enough the other map is drawn along its new direction and the terrain fades over to it (chooseMap):
 * shadows glide with the sun instead of shimmering along their edges.
 *
 * Before the main pass, the chunk sections that can shadow what the camera sees are drawn a second time
 * into a depth texture, looking along the light, with an orthographic projection centred near the camera
 * (its middle follows the camera in steps of about two blocks, MIDDLE_STEP_BLOCKS):
 * solid terrain, then cutout terrain, nearest the light first, so the GPU shades as little of it as it
 * can. The
 * solid, cutout and classic-translucent terrain pipelines are then swapped for copies that also bind
 * that depth texture and its matrix (define ETNA_SHADOWS), and terrain.fsh darkens what the light
 * can't reach. Everything goes through Minecraft's own RenderPearl API, so it runs on Vulkan and
 * OpenGL alike. If any of it fails to compile, shadows switch off and the rest of Etna keeps working.
 *
 * The same block carries the sun's direction and the look settings to the terrain shader, so it is
 * bound whenever Etna is on: with shadows off, in the Nether, at night. EtnaLook2.w tells the shader
 * whether this frame's shadow map is there to read.
 */
public final class EtnaShadows {
	/**
	 * Vanilla's shaders and Etna's in their place, in every copy of a vanilla pipeline Etna draws with (copy):
	 * terrain (also the shadow pass) and clouds.
	 */
	private static final Map<Identifier, Identifier> ETNA_SHADERS = Map.of(
		Identifier.withDefaultNamespace("core/terrain"), Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "core/terrain"),
		Identifier.withDefaultNamespace("core/clouds"), Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "core/clouds")
	);

	/** The terrain block's name, and the name the shafts pass's post chain gives it (PostPassMixin). */
	public static final String BLOCK = "EtnaShadow";
	/** The post chain input that stands for the shadow map: its sampler is EtnaShadowSampler, as on the terrain. */
	public static final String MAP_INPUT = "EtnaShadow";

	/**
	 * Bound to every shadow-receiving terrain draw: the shadow map, the other map while they fade, the block, and
	 * vanilla's cloud texture (EtnaClouds).
	 */
	public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
		.withUniform(MAP_INPUT + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform(MAP_INPUT + "Sampler2", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform(BLOCK, UniformType.UNIFORM_BUFFER)
		.withUniform("EtnaCloudSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();

	/**
	 * The shadow map as a post pass input. A post chain can only name its own targets and textures from the
	 * resource packs, so the shafts pass names main's depth in its place and PostPassMixin swaps in this one.
	 * The map is drawn before the level, outside the frame the chain is part of, so the frame has nothing to
	 * order it by. It is read point by point (NEAREST), as the terrain reads it. While the two maps fade, it
	 * is the one the terrain's block names first (EtnaShadowMat): the one weighing more.
	 */
	public static final PostPass.Input POST_MAP = new PostPass.Input() {
		@Override
		public void addToPass(FramePass pass, Map<Identifier, ResourceHandle<RenderTarget>> targets) {
		}

		@Override
		public GpuTextureView texture(Map<Identifier, ResourceHandle<RenderTarget>> targets) {
			if (views[primary] == null) {
				// Shadows never ran this session (Etna turned on in the middle of a frame): a small, fully lit map.
				ensureResources(IDLE_SIZE);
			}
			return views[primary];
		}

		@Override
		public String samplerName() {
			return MAP_INPUT;
		}

		@Override
		public boolean bilinear() {
			return false;
		}
	};

	// std140: mat4 EtnaShadowMat, then vec4 EtnaLightDir, EtnaShadowInfo, EtnaLook, EtnaLook2, EtnaFaceShade, EtnaWorld, EtnaMaterial,
	// EtnaWater, EtnaWaterColor, EtnaWaterLook, EtnaSkyColor (EtnaWater.writeTerrain), EtnaDark (EtnaDark.writeTerrain), EtnaHand,
	// EtnaHandPos (EtnaTouch.writeTerrain), then the sky's nine (EtnaSky.writeTerrain: EtnaSky0, EtnaSky1, EtnaCloud from
	// EtnaClouds.terrainInfo, EtnaSkySunLow .. EtnaSkyOvercast), then mat4 EtnaShadowMat2 and vec4 EtnaShadowFade for the second
	// map. The block is in assets/etna/shaders/include/terrain_ubo.glsl. The lit clouds read it too (EtnaClouds).
	private static final int UBO_SIZE = 64 + 23 * 16 + 64 + 16 + 4 * 16;
	/** Map size while shadows are off: the terrain shader still declares the sampler. */
	private static final int IDLE_SIZE = 16;
	// Same flags vanilla uses for its projection UBO: uniform buffer, writable by the encoder.
	private static final int UBO_USAGE = 136;
	/** Half the depth range of the shadow volume, in blocks, on each side of the camera. */
	private static final float DEPTH_RANGE = 320.0F;
	/** How strongly the map is squeezed towards the player: ETNA_SHADOW_DISTORTION in shadow_distortion.glsl. */
	private static final float DISTORTION = 0.85F;
	/** Fewest blocks a shadow caster is kept below the lowest thing on screen (see receiverFloor). */
	private static final int FLOOR_MARGIN = 16;
	/**
	 * The widest blur of the shadows that soften with distance from their caster, in blocks
	 * (SHADOW_SPREAD_BLOCKS in terrain_shadow.glsl), and the shadow softness at which they are off.
	 */
	private static final float SPREAD_BLOCKS = 1.2F;
	private static final float SPREAD_OFF_SOFTNESS = 0.25F;
	/**
	 * How far the sun may move the shadow of something STEP_HEIGHT blocks up, on level ground, before the map
	 * follows it (chooseMap): about a texel of the map 20 blocks from the camera at Ultra, three right by it.
	 */
	private static final float STEP_SHIFT = 0.05F;
	private static final float STEP_HEIGHT = 8.0F;
	/**
	 * A fade takes this share of the time since the one before, within FADE_MIN and FADE_MAX seconds: around
	 * noon a step comes about once a second and fades in 0.4 s; low in the sky, where shadows sweep faster,
	 * the fades follow one another. They are short there, so an edge never jumps far in one: with the sun 10
	 * degrees up, the shadow of something 8 blocks high moves 1.4 blocks a second, and a fade of 0.05 s and the
	 * frame or two around it cross over about a tenth of a block (0.2 with 0.15 s, which read as a doubled edge
	 * stepping along).
	 */
	private static final float FADE_SHARE = 0.4F;
	private static final float FADE_MIN = 0.05F;
	private static final float FADE_MAX = 0.6F;
	/**
	 * Light this far from a map's direction (radians, two degrees) starts the maps over: the time set or slept
	 * through, the moon taking over from the sun. So does a camera this many blocks from where a map was drawn
	 * (a teleport): a map is drawn around the camera. So do a new world and a new shadow distance.
	 */
	private static final float RESTART_ANGLE = 0.035F;
	private static final double RESTART_DISTANCE = 16.0;
	/**
	 * The map's middle follows the camera this many blocks at a time (rounded to whole texels of the map), and
	 * only once the camera is MIDDLE_HOLD of a step away from it on either axis of the map: then it moves to the
	 * step nearest the camera, so it can't flick back and forth while the camera stands near the edge of one.
	 * The warp (shadow_distortion.glsl) is centred on the middle, so wherever the middle moves, every texel of the
	 * map lands somewhere else on the world, by some fraction of a texel: the map takes every edge and every
	 * leaf's holes at new points. Until 0.8.1 it followed a texel at a time, which while walking is most frames,
	 * and the dappled shade under trees and the edges further out shimmered as long as the camera moved. Now
	 * the map holds still for two blocks of walking, about half a second, and steps once. The camera stays
	 * within a block and a half of the middle on each axis, where the warp has made texels at most 7% wider
	 * than right on it at a 160-block shadow distance (15% along the line to the middle), and 12% (26%) at 96:
	 * a few thousandths of a block. The shadows end MIDDLE_HOLD of a step plus MIDDLE_SLACK_BLOCKS, about
	 * three and a half blocks, short of the shadow distance (shadowReach), so where they end holds still too.
	 */
	private static final double MIDDLE_STEP_BLOCKS = 2.0;
	private static final double MIDDLE_HOLD = 0.75;
	/**
	 * How far a map's middle can be from the camera when the terrain reads it, on each axis, past the hold above
	 * (a map is read at most a frame after it was drawn, while the maps fade: a camera flying at speed moves up
	 * to a block or two in that frame). The shadows fade out by the same distance from the camera on every side
	 * (EtnaShadowInfo.w), this much inside the map's edge, so wherever the middle is they fade out at the same
	 * place and never meet the map's edge.
	 */
	private static final double MIDDLE_SLACK_BLOCKS = 2.0;
	/**
	 * While the maps fade, each is drawn every other frame and read on the next one too, after the camera may
	 * have turned: its casters are then kept for a screen this many screen widths wider on every side (about
	 * three degrees of turn per frame). A faster turn finishes the fade at once (covers).
	 */
	private static final float FADE_SLACK = 0.05F;
	/**
	 * Less of the other map than this is not worth its second filter in the terrain shader: none at all. The fade
	 * also ends once the old map is down to it.
	 */
	private static final float MIN_MIX = 0.03F;

	/** The shadow pass draws its two terrain layers one at a time: solid first, then cutout. Never modified. */
	private static final ChunkSectionLayer[] SOLID_LAYER = {ChunkSectionLayer.SOLID};
	private static final ChunkSectionLayer[] CUTOUT_LAYER = {ChunkSectionLayer.CUTOUT};

	// The shadow pass pipelines, made for the map's depth format (mapFormat) and made again if it changes.
	// Cutout terrain: single draw, then multi-draw. Solid terrain: the same two drawing only the faces
	// turned towards the light, then the same two drawing both sides (shadowCulling=false).
	private static final @Nullable RenderPipeline[] cutoutShadowPipelines = new RenderPipeline[2];
	private static final @Nullable RenderPipeline[] solidShadowPipelines = new RenderPipeline[4];
	private static final boolean[] solidShadowFailed = new boolean[4];
	private static final Map<RenderPipeline, RenderPipeline> receivers = new IdentityHashMap<>();
	private static final Set<RenderPipeline> failedReceivers = new ReferenceOpenHashSet<>();
	// Copies of vanilla terrain pipelines with Etna's shader and nothing else (overrideFor).
	private static final Map<RenderPipeline, RenderPipeline> plainCopies = new IdentityHashMap<>();
	private static final Set<RenderPipeline> failedPlain = new ReferenceOpenHashSet<>();

	/**
	 * Depth format of the shadow map: 16 bits, about a centimetre a step over its 640 blocks of depth,
	 * finer than the smallest bias any of its readers allows (the water's 2 cm), for half the memory the
	 * shadow pass writes and the terrain reads. 32-bit float if the device can't make it (createMap).
	 */
	private static GpuFormat mapFormat = GpuFormat.D16_UNORM;
	/** The two maps (chooseMap), of textureSize texels a side. */
	private static final @Nullable GpuTexture[] textures = new GpuTexture[2];
	private static final @Nullable GpuTextureView[] views = new GpuTextureView[2];
	private static @Nullable GpuBuffer ubo;
	/** ubo.slice(), kept for the buffer it was made of (uboSlice). */
	private static @Nullable GpuBufferSlice uboSlice;
	private static @Nullable GpuBuffer uboSliceOf;
	private static @Nullable ProjectionMatrixBuffer projectionBuffer;
	private static int textureSize;

	// The two maps' state (chooseMap). Map `base` looks along baseDir; while `fading`, map 1 - base looks
	// along targetDir and the terrain fades over to it (`fade` from 0 to 1, in fadeSeconds).
	/** Each map's matrix, for positions relative to the camera where it was drawn (mapCamera). */
	private static final Matrix4f[] mapMatrix = {new Matrix4f(), new Matrix4f()};
	private static final double[][] mapCamera = new double[2][3];
	/**
	 * The camera's view (EtnaFrame.viewProjection) each map's casters were kept for, and how many screen widths
	 * past SCREEN_SLACK they cover around it: infinite when none were left out for the view (covers).
	 */
	private static final Matrix4f[] mapView = {new Matrix4f(), new Matrix4f()};
	private static final float[] mapSlack = new float[2];
	/** Whether each map holds a drawing along its direction (base's baseDir, the other's targetDir). */
	private static final boolean[] mapDrawn = new boolean[2];
	private static int base;
	private static int lastDrawn;
	private static boolean fading;
	private static float fade;
	private static float fadeSeconds = FADE_MAX;
	private static long lastStepNs;
	private static long lastFrameNs;
	/** EtnaFrame's frame count at the last chooseMap: a frame without one (Etna off, no light) starts over. */
	private static long lastMapFrame = -10L;
	private static final Vector3f baseDir = new Vector3f(0.0F, 1.0F, 0.0F);
	private static final Vector3f targetDir = new Vector3f(0.0F, 1.0F, 0.0F);
	/** The level the maps were drawn in, held weakly: after leaving a world the menus must not keep it alive. */
	private static WeakReference<@Nullable Object> stepLevel = new WeakReference<>(null);
	private static float stepRadius;
	/**
	 * Each map's middle, in blocks along the map's x and y axes from the world's origin (MIDDLE_STEP_BLOCKS), for
	 * the direction and step it was found for: another direction or step finds it afresh. NaN until a map is drawn.
	 */
	private static final double[][] middles = {{Double.NaN, Double.NaN}, {Double.NaN, Double.NaN}};
	private static final Vector3f[] middleDirs = {new Vector3f(), new Vector3f()};
	private static final double[] middleSteps = new double[2];
	/** The camera's place from the middle of the map being drawn, along the map's x and y axes, in blocks. */
	private static float middleLeadX;
	private static float middleLeadY;
	/** The extra screen widths this frame's map keeps casters for (FADE_SLACK while fading), and what it got. */
	private static float drawSlack;
	private static float drawnSlack;
	private static final Matrix4f coverInverse = new Matrix4f();
	private static final Vector4f coverPoint = new Vector4f();
	/**
	 * This frame's maps for the terrain: the one weighing more (primary: EtnaShadowSampler and EtnaShadowMat, the
	 * one read alone by the water, the shafts and single lookups) and how much of the other to mix in (0 .. 0.5).
	 */
	private static int primary;
	private static float secondaryWeight;
	/**
	 * Set by the benchmark (EtnaBenchmark owns it and puts it back): while true, chooseMap keeps a fade running,
	 * held at its middle, along the held direction. Both maps are drawn in turn and every sunlit pixel reads both,
	 * as in a real fade, so the benchmark can measure what the fades cost while the sun moves: it holds the sun,
	 * so no real fade happens during a run.
	 */
	public static boolean benchmarkFade;
	private static final Matrix4f primaryMatrix = new Matrix4f();
	private static final Matrix4f secondaryMatrix = new Matrix4f();
	/**
	 * What the block gets for a map not drawn this frame. Never changed. A shared one rather than a new matrix
	 * where it is chosen: a new object merged with a field in a conditional made the JVM's optimising compiler
	 * (C2, its "reduce allocation merges" step, Temurin 25.0.4 on macOS) crash the game when writeUbo was
	 * compiled anew, the first time a frame drew no map (rain, the sun at the horizon).
	 */
	private static final Matrix4f NO_MAP = new Matrix4f();

	private static final List<SectionRenderDispatcher.RenderSection> savedVisible = new ArrayList<>();
	// Scratch space for collectCasters, kept from frame to frame: the casters found, and one sort key each
	// (their distance towards the light in the high bits, their place in casterSections in the low ones).
	private static SectionRenderDispatcher.RenderSection[] casterSections = new SectionRenderDispatcher.RenderSection[4096];
	private static long[] casterKeys = new long[4096];
	private static final BlockPos.MutableBlockPos casterPos = new BlockPos.MutableBlockPos();
	private static final Vector3f casterCenter = new Vector3f();
	/** This frame's camera matrix (EtnaFrame.viewProjection), for the casters' view test. */
	private static final Matrix4f cameraMatrix = new Matrix4f();
	/** EtnaFrame's frame count at the last prepareFrame, and whether it had moved on since the one before. */
	private static long cameraFrame;
	private static boolean cameraFresh;

	private static boolean failed;
	private static boolean broken;
	private static boolean uboReady;
	private static boolean bindingMainTerrain;

	/** This frame's light, for the post passes (EtnaLook): direction towards the sun, its strength, rain. */
	private static final Vector3f sunDirection = new Vector3f(0.0F, 1.0F, 0.0F);
	private static float lightStrength;
	private static float rain;
	private static boolean moonLight;
	private static boolean sunWorld;
	/** The map's radius in blocks when it was drawn this frame and its block written, else 0 (shaftRadius). */
	private static float shaftRadius;

	// Statistics for the performance log, from the last frame. Read on the render thread. The caster
	// counts are only gathered while the log is on (EtnaConfig.profiling) and the map is drawn, else 0.
	/**
	 * Section positions in the columns the shadow volume covers, every height of the world (those under the
	 * caster floor included), and how many of them were drawn into the map.
	 */
	public static int casterCandidates;
	public static int castersKept;
	/**
	 * Sections left out of the map because their shadow can't reach anything the camera sees (CasterView),
	 * though they passed every other test: castersKept plus these is what the map drew before that test.
	 */
	public static int castersOutsideView;
	/** Indices drawn into the shadow map, solid and cutout terrain. */
	public static long solidIndices;
	public static long cutoutIndices;
	/** Time prepareFrame took on the CPU (light, casters, shadow pass commands, block upload), in nanoseconds. */
	public static long lastSetupNs;

	private EtnaShadows() {
	}

	/**
	 * Called right before vanilla prepares the frame's chunk draws, while no render pass is open: works
	 * out the light, draws the shadow map when it can and fills the block the terrain shader reads.
	 * {@code indirect} says which way vanilla is about to prepare them (multi-draw indirect on Macs):
	 * the shadow pass has to use the same one. The two write the per-frame chunk section data into
	 * different kinds of buffer, and switching kind closes the buffer the other one just used, so
	 * mixing them left the shadow draws reading freed memory (shadows flickering every frame).
	 */
	public static void prepareFrame(LevelRenderer levelRenderer, LevelRenderState state, GpuBufferSlice fog, boolean indirect) {
		long start = System.nanoTime();
		try {
			prepare(levelRenderer, state, fog, indirect);
		} finally {
			lastSetupNs = System.nanoTime() - start;
		}
	}

	private static void prepare(LevelRenderer levelRenderer, LevelRenderState state, GpuBufferSlice fog, boolean indirect) {
		uboReady = false;
		shaftRadius = 0.0F;
		setCasterStats(0, 0, 0, 0L, 0L);
		// EtnaFrame takes the camera's matrix as the level's projection is uploaded, right before the level is
		// drawn. If that has not happened since the last frame (the level drawn some other way), the matrix
		// is an older frame's, and the casters are not tested against it.
		long frame = EtnaFrame.frame();
		cameraFresh = frame != cameraFrame;
		cameraFrame = frame;
		if (!EtnaConfig.enabled) {
			return;
		}
		// The sun, the weather and the dimension also drive the sky glow of the world pass, so they are
		// kept up to date even when the terrain lighting below has failed.
		SkyRenderState sky = state.skyRenderState;
		boolean hasSun = sky.skybox != DimensionType.Skybox.NONE && sky.skybox != DimensionType.Skybox.END;
		// Same rotation the sky applies to the sun quad (up, tilted by the sun angle, turned to the east), on
		// the sun path's tilt (EtnaSky), so the shadows and the casters kept for them follow the sun on screen.
		Vector3f light = EtnaSky.bodyDirection(sky.sunAngle, new Vector3f());
		float strength = smoothstep(0.02F, 0.18F, light.y);
		boolean moon = light.y < 0.0F;
		if (moon) {
			// Night: the moon is opposite the sun and casts fainter shadows, fainter still as it wanes (the
			// new moon keeps half, so the night never loses its shape entirely).
			light.negate();
			strength = 0.35F * (0.5F + 0.5F * EtnaSky.moonLight()) * smoothstep(0.02F, 0.18F, light.y);
		}
		strength *= sky.rainBrightness;
		if (!hasSun) {
			strength = 0.0F;
			moon = false;
		}
		EtnaSky.bodyDirection(sky.sunAngle, sunDirection);
		lightStrength = strength;
		rain = hasSun ? Math.clamp(1.0F - sky.rainBrightness, 0.0F, 1.0F) : 0.0F;
		moonLight = moon;
		sunWorld = hasSun;
		// The mist and the light shafts' air: they need the light, not the map, so they too go on after an error.
		EtnaAir.update(state, light, rain, hasSun);
		if (broken) {
			return;
		}
		try {
			boolean shadows = EtnaConfig.shadowsActive() && !failed;
			int size = shadows ? EtnaConfig.shadowResolution : IDLE_SIZE;
			ensureResources(size);

			boolean drawn = false;
			if (shadows && hasSun && strength > 0.001F) {
				try {
					Vec3 cam = state.cameraRenderState.pos;
					int map = chooseMap(light, cam);
					drawn = renderShadowMap(levelRenderer, state, fog, indirect, map, map == base ? baseDir : targetDir, size);
					if (drawn) {
						settleMaps(cam);
					}
				} catch (RuntimeException e) {
					disable("the shadow pass failed", e);
				}
			}
			if (!drawn) {
				setCasterStats(0, 0, 0, 0L, 0L);
				restartMaps();
			}
			EtnaDark.update(state);
			EtnaTouch.update(state, rain);
			EtnaClouds.update(state, hasSun);
			EtnaWaterSsr.update(state);
			long uboStart = System.nanoTime();
			writeUbo(light, strength, size, drawn, hasSun, moon);
			EtnaProfiler.shadowUbo(System.nanoTime() - uboStart);
			uboReady = true;
			shaftRadius = drawn ? EtnaConfig.shadowDistance : 0.0F;
		} catch (RuntimeException e) {
			broken = true;
			EtnaClient.LOGGER.error("Etna: could not prepare the terrain lighting, Etna's terrain settings are off for this session", e);
		}
	}

	/** Draws map `map` looking along `light`, around the camera, and keeps its matrix (mapMatrix) and the camera's place. */
	private static boolean renderShadowMap(LevelRenderer levelRenderer, LevelRenderState state, GpuBufferSlice fog, boolean indirect,
			int map, Vector3f light, int size) {
		RenderPipeline cutout = cutoutShadowPipeline(indirect);
		if (RenderSystem.getCompiledPipelineNullable(cutout) == null) {
			disable("the shadow pipeline did not compile", null);
			return false;
		}
		// Read once, so the casters and the solid pipeline agree for the whole frame.
		boolean culling = EtnaConfig.shadowCulling;
		RenderPipeline solid = solidShadowPipeline(indirect, culling, cutout);
		float radius = EtnaConfig.shadowDistance;

		Matrix4f rotation = new Matrix4f().lookAlong(-light.x, -light.y, -light.z, 0.0F, 0.0F, 1.0F);
		// The map's middle holds still in the world and follows the camera in steps (MIDDLE_STEP_BLOCKS).
		Vec3 cam = state.cameraRenderState.pos;
		double lx = cam.x * rotation.m00() + cam.y * rotation.m10() + cam.z * rotation.m20();
		double ly = cam.x * rotation.m01() + cam.y * rotation.m11() + cam.z * rotation.m21();
		double[] middle = followMiddle(map, light, middleStep(radius, size), lx, ly);
		middleLeadX = (float) (lx - middle[0]);
		middleLeadY = (float) (ly - middle[1]);
		Matrix4f shadowView = new Matrix4f().translation(middleLeadX, middleLeadY, 0.0F).mul(rotation);

		boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		// Reversed depth like the rest of the game: near and far swapped, so 1 is closest to the light.
		Matrix4f shadowProjection = new Matrix4f().setOrtho(-radius, radius, -radius, radius, DEPTH_RANGE, -DEPTH_RANGE, zeroToOne);
		Matrix4f shadowMatrix = new Matrix4f(shadowProjection).mul(shadowView);

		Minecraft mc = Minecraft.getInstance();
		ChunkSectionsToRender sections = prepareCasters(levelRenderer, cam, rotation, shadowView, light, radius, size, culling, indirect);
		long recordStart = System.nanoTime();
		GpuTextureView atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).textureView();
		GpuTextureView lightmap = mc.gameRenderer.lightmap();
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

		// Uploads have to happen before the pass opens: the encoder refuses commands while one is open.
		GpuBufferSlice projection = projectionBuffer.getBuffer(shadowProjection);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// Cleared as the pass opens (as vanilla does for its clouds): a separate clear first wrote the
		// whole map once more and waited on a full barrier.
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Etna shadow map")
			.withDepthAttachment(views[map], OptionalDouble.of(0.0))
			.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Projection", projection);
			pass.setUniform("Fog", fog);
			// Solid terrain first, through a pipeline with no alpha test: with nothing to discard, the GPU can
			// drop every hidden fragment before shading it. With shadowCulling on it also skips the faces turned
			// away from the light: a ray from the light enters a block through a face turned towards it, nearer
			// the light than any face it leaves by, so the map keeps the same depths. Then cutout terrain
			// (leaves, plants, grass), both sides of every face, which still needs its alpha test, mostly behind
			// the depth the solid blocks have already written. Each call binds its own textures and terrain
			// uniforms again; the map comes out the same as one call.
			sections.renderLayers(SOLID_LAYER, nearest, pass, atlas, lightmap, null, solid, solid);
			sections.renderLayers(CUTOUT_LAYER, nearest, pass, atlas, lightmap, null, cutout, cutout);
		}
		EtnaProfiler.shadowRecord(System.nanoTime() - recordStart);
		mapMatrix[map].set(shadowMatrix);
		mapCamera[map][0] = cam.x;
		mapCamera[map][1] = cam.y;
		mapCamera[map][2] = cam.z;
		mapView[map].set(cameraMatrix);
		mapSlack[map] = drawnSlack;
		mapDrawn[map] = true;
		lastDrawn = map;
		return true;
	}

	/** How far the map's middle moves at a time, in blocks: MIDDLE_STEP_BLOCKS rounded to whole texels of the map. */
	private static double middleStep(float radius, int size) {
		double texel = 2.0 * radius / size;
		return texel * Math.max(1L, Math.round(MIDDLE_STEP_BLOCKS / texel));
	}

	/**
	 * Map `map`'s middle for a camera at lx, ly along its axes (light): where it was, unless the camera has gone
	 * MIDDLE_HOLD of a step past it on an axis, or the map now looks along another direction or the step changed
	 * (another map size or shadow distance); then the step nearest the camera on that axis (on both, for a new
	 * direction or step). A camera far away (a teleport) is far past any hold.
	 */
	private static double[] followMiddle(int map, Vector3f light, double step, double lx, double ly) {
		double[] middle = middles[map];
		boolean fresh = middleSteps[map] != step || !middleDirs[map].equals(light) || !Double.isFinite(middle[0]) || !Double.isFinite(middle[1]);
		double hold = MIDDLE_HOLD * step;
		if (fresh || !(Math.abs(lx - middle[0]) <= hold)) {
			middle[0] = Math.rint(lx / step) * step;
		}
		if (fresh || !(Math.abs(ly - middle[1]) <= hold)) {
			middle[1] = Math.rint(ly / step) * step;
		}
		middleSteps[map] = step;
		middleDirs[map].set(light);
		return middle;
	}

	/**
	 * The farthest the camera can be from a map's middle on either axis when the terrain reads that map, in
	 * blocks: the hold, and a frame of flying (MIDDLE_SLACK_BLOCKS).
	 */
	private static float middleLeadMax(float radius, int size) {
		return (float) (MIDDLE_HOLD * middleStep(radius, size) + MIDDLE_SLACK_BLOCKS);
	}

	/**
	 * Which map to draw this frame. Turned with the sun every frame, the map moved every shadow edge by a whole
	 * texel at a time, here and there along it, as the sun crept on (the area-weighted filter smooths how a
	 * receiver sits in the map, not where the map's texels cut the caster), and shadow edges shimmered all
	 * day. Now a map's direction holds still until the sun has moved the shadow of something STEP_HEIGHT
	 * blocks up by STEP_SHIFT. Then the other map is drawn along the sun's new direction and the terrain fades
	 * over to it: between two maps that hold still, an edge glides instead of flickering. Held still, a map
	 * drawn again every frame comes out the same, texel for texel, while its middle holds still (followMiddle).
	 * While the two fade they take turns being drawn, so each is at most a frame old (settleMaps moves the older
	 * one to this frame's camera) and the shadow pass costs what one map did. A fade starts by drawing the held
	 * map once more, so neither map is ever read on a frame after the one it was drawn for unless its casters
	 * were kept for that (FADE_SLACK); a turn of the camera too fast for them ends the fade on the spot, on the
	 * new map, drawn for this frame (covers).
	 */
	private static int chooseMap(Vector3f light, Vec3 cam) {
		long frame = EtnaFrame.frame();
		if (frame == lastMapFrame && mapDrawn[lastDrawn]) {
			// The level drawn a second time this frame (another mod's view): the same map again, for that
			// view, and the fade stays where it is.
			return lastDrawn;
		}
		long now = System.nanoTime();
		float dt = lastFrameNs == 0L ? 0.0F : Math.clamp((now - lastFrameNs) * 1.0e-9F, 0.0F, 0.1F);
		lastFrameNs = now;
		boolean gap = frame != lastMapFrame + 1L;
		lastMapFrame = frame;
		Object level = Minecraft.getInstance().level;
		int other = 1 - base;
		boolean restart = gap || level != stepLevel.get() || EtnaConfig.shadowDistance != stepRadius || !mapDrawn[base]
			|| angle(baseDir, light) > RESTART_ANGLE || moved(base, cam)
			|| fading && (angle(targetDir, light) > RESTART_ANGLE || (mapDrawn[other] ? moved(other, cam) : fade > 0.0F));
		if (restart) {
			if (level != stepLevel.get()) {
				stepLevel = new WeakReference<>(level);
			}
			stepRadius = EtnaConfig.shadowDistance;
			baseDir.set(light);
			fading = false;
			mapDrawn[other] = false;
			lastStepNs = now;
			drawSlack = 0.0F;
			return base;
		}
		if (benchmarkFade && !fading) {
			// A fade to the direction already held, started as a real one is (the held map drawn once more).
			targetDir.set(baseDir);
			fading = true;
			fade = 0.0F;
			mapDrawn[other] = false;
			drawSlack = FADE_SLACK;
			return base;
		}
		if (fading) {
			// The benchmark's fade stays at its start until the other map is drawn (as a real one does in its
			// first frame), then at its middle, where both maps count the same.
			fade = benchmarkFade ? (mapDrawn[other] ? 0.5F : 0.0F) : fade + dt / fadeSeconds;
			// The map drawn last frame is read again this frame: unless it still covers the view, and the old
			// map still counts (MIN_MIX), the fade ends here.
			if (fade < 1.0F && smoothstep(0.0F, 1.0F, fade) < 1.0F - MIN_MIX && covers(lastDrawn)) {
				drawSlack = FADE_SLACK;
				return lastDrawn == base ? other : base;
			}
			// Faded over: the new map is the one held from now on.
			fading = false;
			mapDrawn[base] = false;
			base = other;
			baseDir.set(targetDir);
			drawSlack = 0.0F;
			return base;
		}
		if (STEP_HEIGHT * shadowShift(baseDir, light) >= STEP_SHIFT) {
			fadeSeconds = Math.clamp(FADE_SHARE * (now - lastStepNs) * 1.0e-9F, FADE_MIN, FADE_MAX);
			lastStepNs = now;
			targetDir.set(light);
			fading = true;
			fade = 0.0F;
			mapDrawn[other] = false;
			// The held map once more, kept for next frame's view too: next frame draws the new one.
			drawSlack = FADE_SLACK;
			return base;
		}
		drawSlack = 0.0F;
		return base;
	}

	/**
	 * Whether map `map`, drawn last frame, holds every caster this frame's view needs: its casters were kept
	 * for the view then (mapView), widened by mapSlack screen widths, and each corner of this frame's screen
	 * must look out through that wider screen. Seen from one view, another view's screen is a four-sided shape
	 * with the corners' images for its corners, so the four corners bound all of it. The camera's own move in
	 * a frame is left to CasterView's SLOP; a jump starts the maps over (moved).
	 */
	private static boolean covers(int map) {
		float slack = mapSlack[map];
		if (slack == Float.POSITIVE_INFINITY) {
			return true;
		}
		if (!(slack >= 0.0F) || !cameraFresh) {
			return false;
		}
		Matrix4f inverse = EtnaFrame.inverseViewProjection(coverInverse);
		Vector4f p = coverPoint;
		// The eye: the one point the projection sends to w = 0. View bobbing moves it off the camera's own
		// position by up to a tenth of a block, a long way next to the near plane, so the rays start there.
		inverse.transform(p.set(0.0F, 0.0F, 1.0F, 0.0F));
		if (!(Math.abs(p.w) > 1e-12F)) {
			return false;
		}
		float eyeX = p.x / p.w;
		float eyeY = p.y / p.w;
		float eyeZ = p.z / p.w;
		for (int i = 0; i < 4; i++) {
			// A point on this frame's ray through the corner, then that ray's direction (w 0) in the old view.
			inverse.transform(p.set((float) (i & 1), (float) (i >> 1), 0.5F, 1.0F));
			if (!(Math.abs(p.w) > 1e-12F)) {
				return false;
			}
			mapView[map].transform(p.set(p.x / p.w - eyeX, p.y / p.w - eyeY, p.z / p.w - eyeZ, 0.0F));
			if (!(p.w > 1e-6F)) {
				return false;
			}
			float u = p.x / p.w;
			float v = p.y / p.w;
			if (!(Math.max(Math.max(-u, u - 1.0F), Math.max(-v, v - 1.0F)) <= slack)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * After this frame's map is drawn: which map the terrain reads first, how much of the other it mixes in (none
	 * near either end of the fade, MIN_MIX), and both matrices moved to this frame's camera (a map drawn last
	 * frame stood where the camera was then).
	 */
	private static void settleMaps(Vec3 cam) {
		int other = 1 - base;
		float toOther = fading && mapDrawn[other] ? smoothstep(0.0F, 1.0F, fade) : 0.0F;
		primary = toOther < 0.5F ? base : other;
		// Below MIN_MIX the other map is left out, and its share grows from there without a step up to 0.5.
		float mix = Math.min(toOther, 1.0F - toOther);
		secondaryWeight = mix < MIN_MIX ? 0.0F : 0.5F * (mix - MIN_MIX) / (0.5F - MIN_MIX);
		mapAtCamera(primary, cam, primaryMatrix);
		if (secondaryWeight > 0.0F) {
			mapAtCamera(1 - primary, cam, secondaryMatrix);
		} else {
			secondaryMatrix.set(primaryMatrix);
		}
	}

	/** Map `map`'s matrix for positions relative to this frame's camera. */
	private static void mapAtCamera(int map, Vec3 cam, Matrix4f out) {
		double[] at = mapCamera[map];
		out.set(mapMatrix[map]).translate((float) (cam.x - at[0]), (float) (cam.y - at[1]), (float) (cam.z - at[2]));
	}

	/** No map to fade from: the next one drawn starts over (shadows off, the light too low, an error). */
	private static void restartMaps() {
		mapDrawn[0] = false;
		mapDrawn[1] = false;
		fading = false;
		secondaryWeight = 0.0F;
	}

	private static boolean moved(int map, Vec3 cam) {
		double[] at = mapCamera[map];
		double dx = cam.x - at[0];
		double dy = cam.y - at[1];
		double dz = cam.z - at[2];
		return dx * dx + dy * dy + dz * dz > RESTART_DISTANCE * RESTART_DISTANCE;
	}

	private static float angle(Vector3f a, Vector3f b) {
		return (float) Math.acos(Math.clamp(a.dot(b) / (a.length() * b.length()), -1.0F, 1.0F));
	}

	/** How far apart, on level ground, the shadows the two directions throw lie, per block of height. */
	private static float shadowShift(Vector3f a, Vector3f b) {
		float ay = Math.max(a.y, 0.1F);
		float by = Math.max(b.y, 0.1F);
		float dx = a.x / ay - b.x / by;
		float dz = a.z / ay - b.z / by;
		return (float) Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Vanilla's prepare methods draw the sections in LevelRenderer.visibleSections, the ones inside the
	 * camera's view. A tree just outside the screen still throws its shadow into it, so for the shadow
	 * pass the list briefly holds the shadow casters instead (collectCasters): shadows no longer pop in and
	 * out as the camera turns.
	 */
	private static ChunkSectionsToRender prepareCasters(LevelRenderer levelRenderer, Vec3 cam, Matrix4f rotation, Matrix4f shadowView,
			Vector3f light, float radius, int size, boolean culling, boolean indirect) {
		ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = levelRenderer.visibleSections();
		savedVisible.clear();
		savedVisible.addAll(visible);
		try {
			long scanStart = System.nanoTime();
			collectCasters(levelRenderer.viewArea(), cam, rotation, light, radius, size, culling, visible);
			long prepareStart = System.nanoTime();
			EtnaProfiler.shadowScan(prepareStart - scanStart);
			ChunkSectionsToRender sections = indirect
				? levelRenderer.prepareChunkRendersIndirect(shadowView, false)
				: levelRenderer.prepareChunkRenders(shadowView, false);
			EtnaProfiler.shadowPrepare(System.nanoTime() - prepareStart);
			return sections;
		} finally {
			visible.clear();
			visible.addAll(savedVisible);
			savedVisible.clear();
		}
	}

	/**
	 * Fills {@code out} with the sections that can throw a shadow into this frame's map, nearest the light
	 * first. A section counts when it has solid or cutout terrain built (water and other translucent terrain
	 * cast no shadow), lies inside the map's square and, with shadowCulling on, does not sit wholly under
	 * receiverFloor and can shadow something the camera sees (CasterView). The tests run cheapest first.
	 * Nearest the light first: the depth test then turns away most of what lies further down before it is
	 * shaded, instead of every layer of ground and cave being shaded over the one below. Vanilla keeps the
	 * list's order inside each of its vertex buffers. The map itself comes out the same in any order.
	 */
	private static void collectCasters(@Nullable ViewArea area, Vec3 cam, Matrix4f rotation, Vector3f light, float radius, int size,
			boolean culling, ObjectArrayList<SectionRenderDispatcher.RenderSection> out) {
		boolean stats = EtnaConfig.profiling != EtnaConfig.Profiling.OFF;
		if (area == null) {
			// No sections to look up: the pass draws what the camera sees, which out still holds. It keeps
			// nothing for any other view.
			drawnSlack = -1.0F;
			setCasterStats(stats ? out.size() : 0, stats ? out.size() : 0, 0, 0L, 0L);
			return;
		}
		out.clear();
		// A section's centre may sit up to half its diagonal (~13.9 blocks) outside the volume and still
		// reach into it. The volume is the map's square round its middle; reach counts from the camera, which can
		// be up to a few blocks from the middle (MIDDLE_STEP_BLOCKS).
		float lead = Math.max(Math.abs(middleLeadX), Math.abs(middleLeadY));
		float reach = radius + 14.0F + lead;
		int sectionRadius = Mth.ceil(reach / 16.0F);
		int camX = SectionPos.blockToSectionCoord(cam.x);
		int camZ = SectionPos.blockToSectionCoord(cam.z);
		int minY = area.minSectionY();
		int maxY = area.maxSectionY();
		// The lowest section whose top (16 blocks above its origin) still reaches the floor.
		// The floor comes from what vanilla draws this frame, so it too belongs to this frame's view. While the
		// maps fade it drops one section more: the map is read again next frame, when a turn may show more below.
		boolean floorTest = culling && light.y > 0.0F;
		int lowY = floorTest
			? Math.max(minY, Math.floorDiv(receiverFloor(cam, rotation, radius, size) - 1 - (drawSlack > 0.0F ? 16 : 0), 16))
			: minY;
		if (cameraFresh) {
			EtnaFrame.viewProjection(cameraMatrix);
		}
		boolean viewTest = culling && cameraFresh
			&& CasterView.setUp(cameraMatrix, light, radius, size, EtnaConfig.shadowSoftness.value, reach, drawSlack);
		// What other views the casters kept also cover (covers): any with nothing left out for the view, none
		// without this frame's camera to tell.
		drawnSlack = !viewTest && !floorTest ? Float.POSITIVE_INFINITY : cameraFresh ? drawSlack : -1.0F;
		if (viewTest) {
			markWater(cam, rotation, reach);
		}
		BlockPos.MutableBlockPos pos = casterPos;
		Vector3f center = casterCenter;
		int kept = 0;
		int outsideView = 0;
		long solidIndexCount = 0L;
		long cutoutIndexCount = 0L;
		for (int sx = camX - sectionRadius; sx <= camX + sectionRadius; sx++) {
			for (int sz = camZ - sectionRadius; sz <= camZ + sectionRadius; sz++) {
				for (int sy = lowY; sy <= maxY; sy++) {
					pos.set(SectionPos.sectionToBlockCoord(sx), SectionPos.sectionToBlockCoord(sy), SectionPos.sectionToBlockCoord(sz));
					SectionRenderDispatcher.RenderSection section = area.getRenderSectionAt(pos);
					if (section == null || section.getSectionNode() != SectionPos.asLong(sx, sy, sz)) {
						continue;
					}
					SectionMesh mesh = section.getSectionMesh();
					if (mesh == CompiledSectionMesh.UNCOMPILED || (mesh.isEmpty(ChunkSectionLayer.SOLID) && mesh.isEmpty(ChunkSectionLayer.CUTOUT))) {
						continue;
					}
					float x = (float) (pos.getX() + 8 - cam.x);
					float y = (float) (pos.getY() + 8 - cam.y);
					float z = (float) (pos.getZ() + 8 - cam.z);
					center.set(x, y, z);
					rotation.transformDirection(center);
					if (Math.abs(center.x + middleLeadX) > radius + 14.0F || Math.abs(center.y + middleLeadY) > radius + 14.0F) {
						continue;
					}
					if (viewTest && !CasterView.keeps(x, y, z, center.x, center.y)) {
						outsideView++;
						continue;
					}
					if (kept == casterKeys.length) {
						casterKeys = Arrays.copyOf(casterKeys, 2 * kept);
						casterSections = Arrays.copyOf(casterSections, 2 * kept);
					}
					// After the rotation, z points towards the light.
					casterKeys[kept] = casterKey(center.z, kept);
					casterSections[kept] = section;
					kept++;
					if (stats) {
						solidIndexCount += indexCount(mesh, ChunkSectionLayer.SOLID);
						cutoutIndexCount += indexCount(mesh, ChunkSectionLayer.CUTOUT);
					}
				}
			}
		}
		// In place, with no allocation.
		LongArrays.quickSort(casterKeys, 0, kept);
		for (int i = 0; i < kept; i++) {
			out.add(casterSections[(int) casterKeys[i]]);
		}
		// Hold on to no section past this frame.
		Arrays.fill(casterSections, 0, kept, null);
		if (stats) {
			int columns = 2 * sectionRadius + 1;
			setCasterStats(columns * columns * (maxY - minY + 1), kept, outsideView, solidIndexCount, cutoutIndexCount);
		} else {
			setCasterStats(0, 0, 0, 0L, 0L);
		}
	}

	/**
	 * Hands CasterView the visible water: every section vanilla draws this frame (savedVisible) with
	 * translucent terrain built, which is where water is (and glass and ice, which only keep a few more
	 * casters than needed).
	 */
	private static void markWater(Vec3 cam, Matrix4f rotation, float reach) {
		CasterView.clearWater(reach);
		float r00 = rotation.m00();
		float r10 = rotation.m10();
		float r20 = rotation.m20();
		float r01 = rotation.m01();
		float r11 = rotation.m11();
		float r21 = rotation.m21();
		for (int i = 0, n = savedVisible.size(); i < n; i++) {
			SectionRenderDispatcher.RenderSection section = savedVisible.get(i);
			SectionMesh mesh = section.getSectionMesh();
			if (mesh == CompiledSectionMesh.UNCOMPILED || mesh.isEmpty(ChunkSectionLayer.TRANSLUCENT)) {
				continue;
			}
			BlockPos origin = section.getRenderOrigin();
			float dx = (float) (origin.getX() + 8 - cam.x);
			float dy = (float) (origin.getY() + 8 - cam.y);
			float dz = (float) (origin.getZ() + 8 - cam.z);
			CasterView.addWater(r00 * dx + r10 * dy + r20 * dz, r01 * dx + r11 * dy + r21 * dz);
		}
	}

	/**
	 * The lowest height, in blocks, at which a shadow caster can still change a pixel this frame. The map is
	 * only drawn while its light comes from above (light.y > 0.02), so a caster only ever darkens what lies
	 * below its own top. Everything the map is read for lies in a section vanilla draws this frame
	 * (savedVisible), or, for the light shafts, in the air between the camera and such a section. A caster
	 * lower than all of that, by more than the shadow filter reaches, can't change any of it. Looking down
	 * into a valley, the valley floor is on screen, so everything above it stays; in a cave the floor is under
	 * the cave, so nothing is dropped there. shadowCulling=false in etna.properties turns this off.
	 */
	private static int receiverFloor(Vec3 cam, Matrix4f rotation, float radius, int size) {
		float softness = EtnaConfig.shadowSoftness.value;
		// How far the shadow filter (shadowVisibility in terrain_shadow.glsl) reaches from a pixel, in texels
		// of the map there. Sideways: its box (softness + 1 texels each way) plus the 1.2-texel push off the
		// surface, along both axes. Along the light: its receiver plane, at most 8 texels of depth per texel
		// over softness + 0.5 texels on each axis. The leaf light (a 3x3 box) and the shafts (one texel)
		// reach less; the shadows that soften with distance reach further near the middle of the map, where
		// the texels are small (floorMargin).
		float reachTexels = 1.415F * (softness + 2.2F) + 16.0F * (softness + 0.5F);
		// A texel at the edge of the map, in blocks; the distortion shrinks it towards the middle.
		float edgeTexel = 2.0F * radius / size / (1.0F - DISTORTION);
		// shadowVisibility pushes its lookup 1.2 texels off the surface (texels of the map where the receiver
		// is) before it checks for the edge of the map. So a receiver up to that far outside the map still
		// reads it, and a lookup can lie that much further out than its receiver. The push is largest where the
		// texels are largest: on the map's edge and past it, where the warp (shadow_distortion.glsl) is at its
		// coarsest and a texel is edgeTexel wide.
		float push = 1.2F * edgeTexel;
		// A section centre further out than this, on either axis, has no point whose lookup lands in the map.
		float mapEdge = radius + 15.0F + push;
		// The most any section can need: a section that can't lower the floor even with it is skipped without
		// the arithmetic.
		int largestMargin = floorMargin(softness, edgeTexel, reachTexels, radius);
		float r00 = rotation.m00();
		float r10 = rotation.m10();
		float r20 = rotation.m20();
		float r01 = rotation.m01();
		float r11 = rotation.m11();
		float r21 = rotation.m21();
		// The shafts start at the camera.
		int floor = Mth.floor(cam.y) - FLOOR_MARGIN;
		for (int i = 0, n = savedVisible.size(); i < n; i++) {
			BlockPos origin = savedVisible.get(i).getRenderOrigin();
			int y = origin.getY();
			if (y - largestMargin >= floor) {
				continue;
			}
			float dx = (float) (origin.getX() + 8 - cam.x);
			float dy = (float) (y + 8 - cam.y);
			float dz = (float) (origin.getZ() + 8 - cam.z);
			float lx = r00 * dx + r10 * dy + r20 * dz;
			float ly = r01 * dx + r11 * dy + r21 * dz;
			int margin = FLOOR_MARGIN;
			// From the map's middle rather than the camera.
			lx += middleLeadX;
			ly += middleLeadY;
			if (Math.abs(lx) <= mapEdge && Math.abs(ly) <= mapEdge) {
				// Read through the map, so filtered there: its texels are largest at its lookup farthest from the
				// middle of the map, which is its own farthest point pushed out once more (in map units; the
				// warp's distance never passes 1, see texelAt).
				float far = Math.min(((float) Math.sqrt(lx * lx + ly * ly) + 15.0F + push) / radius, 1.0F);
				float factor = 1.0F - DISTORTION + DISTORTION * far;
				margin = floorMargin(softness, edgeTexel * factor * factor, reachTexels, radius);
			}
			floor = Math.min(floor, y - margin);
		}
		return floor;
	}

	/**
	 * receiverFloor's margin below a section whose lookups land on texels up to texelBlocks wide: the filter's
	 * reach (reachTexels of those texels), or the wider one of the shadows that soften with distance, sideways
	 * and behind, plus two blocks; at least FLOOR_MARGIN.
	 */
	private static int floorMargin(float softness, float texelBlocks, float reachTexels, float radius) {
		float spread = spreadSideways(softness, texelBlocks, radius) + spreadBehind(softness, texelBlocks);
		return Math.max(FLOOR_MARGIN, Mth.ceil(Math.max(texelBlocks * reachTexels, spread)) + 2);
	}

	/**
	 * How far sideways, in blocks, the shadows that soften with distance (terrain_shadow.glsl) read the map round
	 * a point whose texels are at most texelBlocks wide, in a map `radius` blocks across each way; 0 when they
	 * are off. They look for casters SPREAD_BLOCKS round the point, counted in texels of the point's own size;
	 * further out the texels grow, by the most near the middle of the map, where the distortion grows them
	 * fastest. Then the push off the surface (up to 1.5 texels) and the four texels round each read (up to 2.2
	 * texels further).
	 */
	private static float spreadSideways(float softness, float texelBlocks, float radius) {
		if (!(softness > SPREAD_OFF_SOFTNESS)) {
			return 0.0F;
		}
		float grow = 1.0F + DISTORTION * (SPREAD_BLOCKS + 2.0F) / ((1.0F - DISTORTION) * radius);
		return SPREAD_BLOCKS * grow * grow + 4.0F * texelBlocks;
	}

	/**
	 * How far behind a point, away from the light, the same shadows compare the map with the surface's plane, in
	 * blocks; 0 when they are off. The plane climbs at most 8 texels of depth per texel on each axis (the clamp
	 * in etnaShadowFilter); their disc reaches SPREAD_BLOCKS, or softness + 1 texels where that is wider, plus a
	 * texel on each axis for the reads' own texels.
	 */
	private static float spreadBehind(float softness, float texelBlocks) {
		if (!(softness > SPREAD_OFF_SOFTNESS)) {
			return 0.0F;
		}
		return 8.0F * (1.4143F * Math.max(SPREAD_BLOCKS, (softness + 1.0F) * texelBlocks) + 2.0F * texelBlocks);
	}

	/** Sort key: nearest the light (largest z) first, then the order found, which the low 32 bits give back. */
	private static long casterKey(float towardsLight, int index) {
		int bits = Float.floatToIntBits(-towardsLight);
		// Flip the magnitude bits of negative floats, so the ints order the way the floats do.
		bits ^= (bits >> 31) & 0x7FFFFFFF;
		return ((long) bits << 32) | index;
	}

	private static int indexCount(SectionMesh mesh, ChunkSectionLayer layer) {
		SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
		return draw == null ? 0 : draw.indexCount();
	}

	private static void setCasterStats(int candidates, int kept, int outsideView, long solid, long cutout) {
		casterCandidates = candidates;
		castersKept = kept;
		castersOutsideView = outsideView;
		solidIndices = solid;
		cutoutIndices = cutout;
	}

	/**
	 * collectCasters' test of whether a section's shadow can reach anything the camera sees, set up once per
	 * frame: setUp, then clearWater and addWater for each visible section with water.
	 *
	 * The camera sees a pyramid with its tip at the eye, bounded by the four sides of the screen: no near
	 * plane (the light shafts start at the eye) and no far plane (the map's square already bounds the
	 * casters). A point's shadow falls into the view when the view, moved some distance towards the light,
	 * takes the point in. After L. Spiro's "Tightly Culling Shadow Casters for Directional Lights" (see
	 * CREDITS.md), the view extruded that way is bounded by the sides that face away from the light (moving
	 * towards the light never takes a point out of those) and by one plane along the light through each edge
	 * where such a side meets a side facing the light. That edge plane is the two sides blended with weights
	 * that make it parallel to the light: whatever lies inside both sides lies inside it, and moving along the
	 * light never takes a point out of it. So no plane drops a point whose shadow can reach the view, whatever
	 * the light's direction, and however rounding sorts a side lying almost along the light. With the light
	 * behind the camera and inside its view every side faces it and there is no plane: every shadow can reach
	 * the view then, and nothing is dropped.
	 *
	 * The planes are then moved out by how far around a point the map's readers look, which grows with the
	 * map's texels and so with the distance from its middle. The water's depth reads the map behind the
	 * water's surface, away from the light, so the columns along the light under visible water are kept
	 * whole (addWater).
	 */
	private static final class CasterView {
		/** Width of the distance bands the margins are worked out for, in blocks from the middle of the map. */
		private static final float BAND = 16.0F;
		private static final int MAX_BANDS = 64;
		/** Half a section's diagonal: how far its box reaches from its centre. */
		private static final float SECTION_RADIUS = 13.86F;
		/** Extra room, in blocks: the eye moved by view bobbing (about a tenth of a block), rounding. */
		private static final float SLOP = 1.0F;
		/**
		 * How far each side of the screen is widened, in screen widths: anti-aliasing moves the picture by up
		 * to half a pixel, which this covers on windows down to 200 pixels.
		 */
		private static final float SCREEN_SLACK = 0.0025F;
		/** The diagonal of a square, per unit of its side. */
		private static final float DIAGONAL = 1.4143F;

		// The four sides of the screen, normal (x, y, z) and offset, in order around the screen, and how much
		// each normal points towards the light.
		private static final float[] sides = new float[16];
		private static final float[] towards = new float[4];
		// Up to four sides and four edge planes: normal (x, y, z), offset, how far a section's box reaches
		// along the normal, and how much the normal points towards the light (0 for an edge plane).
		private static final float[] planes = new float[8 * 6];
		private static int planeCount;
		// Per distance band: how far the readers of the map look sideways around a point, how far behind it
		// (away from the light) the shadow filter's receiver plane reaches, and how far the water's depth
		// looks around a point of the surface.
		private static final float[] side = new float[MAX_BANDS];
		private static final float[] behind = new float[MAX_BANDS];
		private static final float[] water = new float[MAX_BANDS];
		private static int bands;
		// The map's square in the light's frame, cut into BAND-sized cells: true where a caster can hold the
		// bottom under visible water.
		private static boolean[] waterCells = new boolean[0];
		private static int gridSize;
		private static float gridOrigin;

		private CasterView() {
		}

		/**
		 * Sets the test up for this frame: {@code camera} takes camera-relative points to the screen
		 * (EtnaFrame.viewProjection), {@code light} points towards the light, {@code reach} is how far a
		 * caster's centre can be from the camera along the map's axes, {@code slack} how many screen widths
		 * further on every side the casters are kept for (a map read again next frame). False when the test
		 * can't drop anything: the light shines into the view from behind, the matrix is broken, or the map's
		 * texels are too coarse for its size to bound how far its readers look.
		 */
		static boolean setUp(Matrix4f camera, Vector3f light, float radius, int size, float softness, float reach, float slack) {
			planeCount = 0;
			// u = row 0 / row 3 and v = row 1 / row 3 of the matrix run from 0 to 1 across the screen, so the
			// sides are u >= 0, v >= 0, u <= 1 and v <= 1, each widened by SCREEN_SLACK and `slack`. In this
			// order each one shares an edge with the next, and the last with the first.
			Matrix4f m = camera;
			float s = SCREEN_SLACK + slack;
			float t = 1.0F + s;
			if (!setSide(0, m.m00() + s * m.m03(), m.m10() + s * m.m13(), m.m20() + s * m.m23(), m.m30() + s * m.m33(), light)
				|| !setSide(1, m.m01() + s * m.m03(), m.m11() + s * m.m13(), m.m21() + s * m.m23(), m.m31() + s * m.m33(), light)
				|| !setSide(2, t * m.m03() - m.m00(), t * m.m13() - m.m10(), t * m.m23() - m.m20(), t * m.m33() - m.m30(), light)
				|| !setSide(3, t * m.m03() - m.m01(), t * m.m13() - m.m11(), t * m.m23() - m.m21(), t * m.m33() - m.m31(), light)) {
				return false;
			}
			for (int i = 0; i < 4; i++) {
				if (towards[i] >= 0.0F) {
					addPlane(sides[4 * i], sides[4 * i + 1], sides[4 * i + 2], sides[4 * i + 3], towards[i]);
				}
			}
			for (int i = 0; i < 4; i++) {
				int j = (i + 1) & 3;
				if ((towards[i] >= 0.0F) != (towards[j] >= 0.0F)) {
					// One side faces away from the light and the other towards it: |towards j| times side i plus
					// |towards i| times side j is parallel to the light, and holds whatever both sides hold.
					float a = Math.abs(towards[j]);
					float b = Math.abs(towards[i]);
					float x = a * sides[4 * i] + b * sides[4 * j];
					float y = a * sides[4 * i + 1] + b * sides[4 * j + 1];
					float z = a * sides[4 * i + 2] + b * sides[4 * j + 2];
					float w = a * sides[4 * i + 3] + b * sides[4 * j + 3];
					float length = (float) Math.sqrt(x * x + y * y + z * z);
					// The light almost along the edge: leaving the plane out only keeps more.
					if (length > 1e-4F) {
						addPlane(x / length, y / length, z / length, w / length, 0.0F);
					}
				}
			}
			return planeCount > 0 && setMargins(radius, size, softness, reach);
		}

		private static boolean setSide(int i, float x, float y, float z, float w, Vector3f light) {
			float length = (float) Math.sqrt(x * x + y * y + z * z);
			if (!(length > 1e-6F) || !Float.isFinite(length) || !Float.isFinite(w)) {
				return false;
			}
			x /= length;
			y /= length;
			z /= length;
			sides[4 * i] = x;
			sides[4 * i + 1] = y;
			sides[4 * i + 2] = z;
			sides[4 * i + 3] = w / length;
			towards[i] = x * light.x + y * light.y + z * light.z;
			return true;
		}

		private static void addPlane(float x, float y, float z, float w, float towardsLight) {
			int o = 6 * planeCount++;
			planes[o] = x;
			planes[o + 1] = y;
			planes[o + 2] = z;
			planes[o + 3] = w;
			planes[o + 4] = 8.0F * (Math.abs(x) + Math.abs(y) + Math.abs(z));
			planes[o + 5] = towardsLight;
		}

		/**
		 * The margins for each band of distance from the middle of the map. A reader of the map looks a number
		 * of texels around a point (its anchor), and the distortion makes texels larger away from the middle:
		 * within d blocks of it a texel is at most edgeTexel * f^2 blocks wide in any direction, with
		 * f = 0.15 + 0.85 min(d / radius, 1) (shadow_distortion.glsl takes f from a distance of its own that
		 * is never larger than either, and its texels are never wider than f^2 makes them). How far a reader
		 * looks so depends on how far out it looks: settle finds a reach that covers itself.
		 */
		private static boolean setMargins(float radius, int size, float softness, float reach) {
			float texel = 2.0F * radius / size;
			float edgeTexel = texel / (1.0F - DISTORTION);
			// How far the map's middle is from the camera (it follows the camera in steps, MIDDLE_STEP_BLOCKS).
			float snap = (float) Math.sqrt(middleLeadX * middleLeadX + middleLeadY * middleLeadY);
			// In texels: the shadow filter's square (softness texels each way, so the texels it weighs have their
			// centres up to softness + 0.5 away on each axis) and its push off the surface (1.2 texels); the
			// light through leaves reads 1.5 texels each way around a point moved towards the light.
			float filter = Math.max(DIAGONAL * (softness + 0.5F) + 1.2F, DIAGONAL * 1.5F);
			// Every reader checks that its anchor lies inside the map's square, within its diagonal of the middle,
			// so no texel it reads is wider than at that distance plus its reach.
			float widest = settle(DIAGONAL * radius + snap, 1.0F, radius, edgeTexel, filter, Float.MAX_VALUE);
			if (widest < 0.0F) {
				return false;
			}
			float largestTexel = texelAt(DIAGONAL * radius + snap + widest, radius, edgeTexel);
			bands = Math.min(MAX_BANDS, (int) (DIAGONAL * reach / BAND) + 2);
			for (int b = 0; b < bands; b++) {
				// The farthest a point of a section in this band lies from the middle of the map. A reader that
				// reads that point has its anchor within look of it, so it reads nothing further than far + 2 look.
				float far = (b + 1) * BAND + SECTION_RADIUS + snap;
				float look = settle(far, 2.0F, radius, edgeTexel, filter, largestTexel);
				if (look < 0.0F) {
					return false;
				}
				float texelBlocks = Math.min(texelAt(far + 2.0F * look, radius, edgeTexel), largestTexel);
				// Sideways: the filter, the leaf light, and the light from one block above a point under water
				// (terrain_water.glsl), with the texel its point lands in.
				// The shadows that soften with distance read wider still.
				side[b] = Math.max(Math.max(filter * texelBlocks, 1.0F + 0.71F * texelBlocks), spreadSideways(softness, texelBlocks, radius))
					+ SLOP;
				// The filter compares each texel with the receiver's own plane there, which can lie up to 8 texels
				// of depth per texel further from the light, over softness + 0.5 texels on each axis (over the
				// wider disc, for the shadows that soften with distance).
				behind[b] = Math.max(16.0F * (softness + 0.5F) * texelBlocks, spreadBehind(softness, texelBlocks));
				// The water's depth: the four texels around the surface point, and twelve points up to 4 blocks
				// from it on the water's plane (THICKNESS_RING in terrain_water.glsl), with the texel each lands in.
				water[b] = Math.max(4.0F + 0.71F * texelBlocks, DIAGONAL * texelBlocks) + SLOP;
			}
			return true;
		}

		/**
		 * The reach of the map's readers, in blocks, when every texel they read lies within distance + times *
		 * reach of the middle of the map (and is at most largestTexel wide): the smallest reach that covers
		 * itself, or -1 if there is none. Stepping up from 0, each round reaches further, until a round reaches
		 * no further than the one before: then the reach really is at least as large as what it needs (the
		 * small step added each round makes sure that happens). A map too coarse for its size never gets there,
		 * and the test stays off.
		 */
		private static float settle(float distance, float times, float radius, float edgeTexel, float filter, float largestTexel) {
			float look = 0.0F;
			for (int round = 0; round < 256; round++) {
				float next = readerReach(Math.min(texelAt(distance + times * look, radius, edgeTexel), largestTexel), filter);
				if (next <= look) {
					return look;
				}
				look = next + 0.01F;
				if (!(look < 4.0F * radius + 64.0F)) {
					break;
				}
			}
			return -1.0F;
		}

		/** The widest a texel of the map gets within {@code distance} blocks of its middle, in blocks. */
		private static float texelAt(float distance, float radius, float edgeTexel) {
			float factor = 1.0F - DISTORTION + DISTORTION * Math.min(distance / radius, 1.0F);
			return edgeTexel * factor * factor;
		}

		/** How far from a point any reader of the map looks, in blocks, where its texels are texelBlocks wide. */
		private static float readerReach(float texelBlocks, float filter) {
			return Math.max(filter * texelBlocks, 4.0F + 0.71F * texelBlocks);
		}

		/** Starts this frame's water, with none yet. */
		static void clearWater(float reach) {
			// One cell more than the square needs, so its far edge falls inside too.
			gridOrigin = -reach;
			gridSize = (int) (2.0F * reach / BAND) + 2;
			int cells = gridSize * gridSize;
			if (waterCells.length < cells) {
				waterCells = new boolean[cells];
			} else {
				Arrays.fill(waterCells, 0, cells, false);
			}
		}

		/**
		 * A visible section with water in it, whose centre lies at lightX, lightY from the camera in the light's
		 * frame. The water's depth reads the map anywhere down the column of light under the surface, so every
		 * caster whose centre lies close enough to that column is kept, however far along the light.
		 */
		static void addWater(float lightX, float lightY) {
			int band = (int) ((float) Math.sqrt(lightX * lightX + lightY * lightY) / BAND);
			if (band >= bands) {
				// Wholly outside the map: its depth is never read.
				return;
			}
			float r = 2.0F * SECTION_RADIUS + water[band];
			int x0 = Math.max(0, cell(lightX - r));
			int x1 = Math.min(gridSize - 1, cell(lightX + r));
			int y0 = Math.max(0, cell(lightY - r));
			int y1 = Math.min(gridSize - 1, cell(lightY + r));
			for (int y = y0; y <= y1; y++) {
				for (int x = x0; x <= x1; x++) {
					waterCells[y * gridSize + x] = true;
				}
			}
		}

		private static int cell(float coordinate) {
			return (int) Math.floor((coordinate - gridOrigin) / BAND);
		}

		/**
		 * Whether a section's shadow can reach the view: x, y, z is its centre from the camera, lightX and
		 * lightY the same in the light's frame (inside the map's square, which collectCasters checks first).
		 */
		static boolean keeps(float x, float y, float z, float lightX, float lightY) {
			int band = (int) ((float) Math.sqrt(lightX * lightX + lightY * lightY) / BAND);
			if (band >= bands) {
				return true;
			}
			float sideways = side[band];
			float back = behind[band];
			for (int i = 0, o = 0; i < planeCount; i++, o += 6) {
				float distance = planes[o] * x + planes[o + 1] * y + planes[o + 2] * z + planes[o + 3];
				if (distance + planes[o + 4] + sideways + back * planes[o + 5] < 0.0F) {
					int cellX = cell(lightX);
					int cellY = cell(lightY);
					return cellX >= 0 && cellY >= 0 && cellX < gridSize && cellY < gridSize && waterCells[cellY * gridSize + cellX];
				}
			}
			return true;
		}
	}

	/** Start of ChunkSectionsToRender.renderLayers: hand the light and shadow map to vanilla terrain draws. */
	public static void beginTerrain(RenderPass pass, @Nullable RenderPipeline override, @Nullable RenderPipeline overrideMultidraw) {
		bindingMainTerrain = false;
		if (!uboReady || override != null || overrideMultidraw != null) {
			return;
		}
		bindLight(pass);
		bindingMainTerrain = true;
	}

	/**
	 * Binds what LAYOUT names on a pass: the two shadow maps, the block and the cloud texture. For the shadow-receiving
	 * terrain (beginTerrain) and for Etna's lit entities and items (EtnaEntities). Only while the block is ready
	 * (lightBlock), which also means the maps are there.
	 */
	static void bindLight(RenderPass pass) {
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		pass.setUniform("EtnaShadowSampler", views[primary], nearest);
		pass.setUniform("EtnaShadowSampler2", views[1 - primary], nearest);
		pass.setUniform("EtnaShadow", uboSlice());
		// Bilinear, and wrapping round as the clouds repeat.
		pass.setUniform("EtnaCloudSampler", EtnaClouds.texture(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
	}

	/** The whole block as one slice, made once per buffer: bindLight runs for every lit entity draw. */
	private static GpuBufferSlice uboSlice() {
		GpuBuffer buffer = ubo;
		if (uboSliceOf != buffer || uboSlice == null) {
			uboSlice = buffer.slice();
			uboSliceOf = buffer;
		}
		return uboSlice;
	}

	public static void endTerrain() {
		bindingMainTerrain = false;
	}

	/** After a resource reload Etna's shaders are compiled anew, so a copy that failed before gets another try. */
	public static void resourcesReloaded() {
		failedReceivers.clear();
		failedPlain.clear();
		Arrays.fill(solidShadowFailed, false);
	}

	/**
	 * ChunkSectionsToRender.renderLayers' override pipelines (ChunkSectionsToRenderMixin): while Etna is on, a
	 * vanilla terrain pipeline passed in as an override (Improved Transparency's phases, the debug wireframe)
	 * draws through its copy with Etna's terrain shader, without the shadow block, as before 0.7.1 when Etna's
	 * shader stood in for vanilla's. Improved Transparency's (the OIT define) draw the translucent layer only, so
	 * their copies tell water by any colour in its vertices, as in the classic translucent pass: swamp and
	 * mangrove water too. Others may draw the solid and cutout layers, whose tinted grass and leaves aren't
	 * water. Etna's own shadow pass pipelines, and anything else, pass through.
	 */
	public static @Nullable RenderPipeline overrideFor(@Nullable RenderPipeline override) {
		if (override != null && override.getShaderDefines().flags().contains("OIT")) {
			// Improved Transparency draws this frame: its water leaves no mask for the reflections (EtnaWaterSsr).
			EtnaWaterSsr.sawImprovedTransparency();
		}
		if (override == null || !EtnaConfig.enabled || broken || failedPlain.contains(override)
			|| override.getShaders().values().stream().noneMatch(ETNA_SHADERS::containsKey)) {
			return override;
		}
		RenderPipeline plain = plainCopies.computeIfAbsent(override, p -> {
			RenderPipeline.Builder builder = copy(p, true);
			if (p.getShaderDefines().flags().contains("OIT")) {
				builder = builder.withShaderDefine("ETNA_TRANSLUCENT_LAYER");
			}
			return builder
				.withLocation(Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "plain/" + p.getLocation().getPath()))
				.build();
		});
		if (RenderSystem.getCompiledPipelineNullable(plain) == null) {
			failedPlain.add(override);
			EtnaClient.LOGGER.error("Etna: {} did not compile, that layer draws as vanilla's", plain.getLocation());
			return override;
		}
		return plain;
	}

	/** ChunkSectionLayer.pipeline: inside a shadowed terrain draw, use the shadow-receiving copy. */
	public static RenderPipeline receiverFor(RenderPipeline vanilla) {
		if (!bindingMainTerrain || failedReceivers.contains(vanilla)) {
			return vanilla;
		}
		RenderPipeline receiver = receivers.computeIfAbsent(vanilla, p -> {
			RenderPipeline.Builder builder = copy(p, true).withShaderDefine("ETNA_SHADOWS");
			if (!EtnaConfig.shadowGather) {
				// shadowGather=false in the file: the filter reads one texel at a time (terrain_shadow.glsl's
				// texelFetch path, as in 0.5.0), in case a driver gets textureGather wrong.
				builder.withShaderDefine("ETNA_SHADOW_FETCH");
			}
			if (p == RenderPipelines.TRANSLUCENT_TERRAIN || p == RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW) {
				// Classic-transparency water and glass: the shader can tell them from cutout terrain, which
				// also tests alpha. Their alpha marks the water for the reflections (EtnaWaterSsr.MASK_TARGET).
				builder = builder.withShaderDefine("ETNA_TRANSLUCENT_LAYER").withColorTargetState(EtnaWaterSsr.MASK_TARGET);
			}
			return builder
				.withBindGroupLayout(LAYOUT)
				.withLocation(Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "shadowed/" + p.getLocation().getPath()))
				.build();
		});
		if (RenderSystem.getCompiledPipelineNullable(receiver) == null) {
			failedReceivers.add(vanilla);
			EtnaClient.LOGGER.error("Etna: {} did not compile, that layer draws without shadows", receiver.getLocation());
			return vanilla;
		}
		return receiver;
	}

	/** The shadow pipeline for cutout terrain: both sides of every face, alpha test at 0.5. */
	private static RenderPipeline cutoutShadowPipeline(boolean indirect) {
		int i = indirect ? 1 : 0;
		RenderPipeline pipeline = cutoutShadowPipelines[i];
		if (pipeline == null) {
			pipeline = indirect
				? shadowCopy(RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, "pipeline/shadow_terrain_multidraw", false)
				: shadowCopy(RenderPipelines.CUTOUT_TERRAIN, "pipeline/shadow_terrain", false);
			cutoutShadowPipelines[i] = pipeline;
		}
		return pipeline;
	}

	/**
	 * The shadow pipeline for solid terrain, which has no alpha test and, with {@code culling}, draws only the
	 * faces turned towards the light. If it does not compile, solid terrain goes through the cutout one, as it
	 * did before this one existed: the alpha test and the hidden faces only cost time there.
	 */
	private static RenderPipeline solidShadowPipeline(boolean indirect, boolean culling, RenderPipeline cutout) {
		int i = (indirect ? 1 : 0) + (culling ? 0 : 2);
		if (solidShadowFailed[i]) {
			return cutout;
		}
		RenderPipeline solid = solidShadowPipelines[i];
		if (solid == null) {
			String path = "pipeline/shadow_solid" + (indirect ? "_multidraw" : "") + (culling ? "" : "_double_sided");
			solid = shadowCopy(indirect ? RenderPipelines.SOLID_TERRAIN_MULTIDRAW : RenderPipelines.SOLID_TERRAIN, path, culling);
			solidShadowPipelines[i] = solid;
		}
		if (RenderSystem.getCompiledPipelineNullable(solid) == null) {
			solidShadowFailed[i] = true;
			EtnaClient.LOGGER.warn("Etna: {} did not compile, solid terrain casts its shadows through the cutout pipeline", solid.getLocation());
			return cutout;
		}
		return solid;
	}

	/**
	 * A terrain pipeline with no colour target, for the shadow map: the cutout copy keeps the alpha test at
	 * 0.5, so leaves cast leafy shadows; the solid copy has none. {@code cull} drops the faces turned away from
	 * the light: the shadow pass looks along the light with the same handedness as the camera (its projection
	 * flips depth, not x or y, and the distortion in terrain.vsh keeps every triangle's orientation), so
	 * those are the back faces.
	 */
	private static RenderPipeline shadowCopy(RenderPipeline vanilla, String path, boolean cull) {
		return copy(vanilla, false)
			.withShaderDefine("ETNA_SHADOW_PASS")
			.withCull(cull)
			.withDepthStencilFormat(mapFormat)
			.withLocation(Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, path))
			.build();
	}

	/**
	 * A builder that starts as an exact copy of a vanilla pipeline, drawn with Etna's own terrain or cloud
	 * shader (ETNA_SHADERS) in place of vanilla's. Vanilla's own shaders are never overridden: with Etna off,
	 * the game draws with its own pipelines and shaders and looks exactly as without Etna.
	 */
	static RenderPipeline.Builder copy(RenderPipeline pipeline, boolean keepColorTargets) {
		ColorTargetState[] colors = keepColorTargets
			? pipeline.getColorTargetStates().toArray(new ColorTargetState[0])
			: new ColorTargetState[0];
		List<VertexFormat> formats = new ArrayList<>(pipeline.getVertexFormatBindings());
		var shaders = new HashMap<>(pipeline.getShaders());
		shaders.replaceAll((type, shader) -> ETNA_SHADERS.getOrDefault(shader, shader));
		RenderPipeline.Snippet snippet = new RenderPipeline.Snippet(
			shaders,
			Optional.of(pipeline.getShaderDefines()),
			Optional.of(pipeline.getBindGroupLayouts()),
			colors,
			colors.length,
			Optional.ofNullable(pipeline.getDepthStencilState()),
			Optional.of(pipeline.getDepthStencilFormat()),
			Optional.of(pipeline.getPolygonMode()),
			Optional.of(pipeline.isCull()),
			formats.toArray(new VertexFormat[0]),
			Optional.of(pipeline.getPrimitiveTopology()),
			pipeline.pushConstantSize()
		);
		return RenderPipeline.builder(snippet);
	}

	private static void ensureResources(int size) {
		GpuDevice device = RenderSystem.getDevice();
		if (textures[0] == null || textures[1] == null || views[0] == null || views[1] == null || textureSize != size) {
			// Both closed and cleared first: if making one throws, none is left behind closed but still named,
			// and the next frame makes them again whatever the size.
			textureSize = 0;
			for (int i = 0; i < 2; i++) {
				if (views[i] != null) {
					views[i].close();
					views[i] = null;
				}
				if (textures[i] != null) {
					textures[i].close();
					textures[i] = null;
				}
			}
			for (int i = 0; i < 2; i++) {
				textures[i] = createMap(device, size);
				views[i] = device.createTextureView(textures[i]);
				// Sampled even on nights the map is not drawn: start it fully lit, not undefined.
				device.createCommandEncoder().clearDepthTexture(textures[i], 0.0);
			}
			textureSize = size;
			restartMaps();
		}
		if (ubo == null) {
			ubo = device.createBuffer(() -> "Etna shadow UBO", UBO_USAGE, UBO_SIZE);
		}
		if (projectionBuffer == null) {
			projectionBuffer = new ProjectionMatrixBuffer("etna_shadow");
		}
	}

	/**
	 * The shadow map's depth texture, in mapFormat. A device that can't make a 16-bit one gets a 32-bit float
	 * one, as before 0.6.0, and the shadow pass pipelines are made again for it.
	 */
	private static GpuTexture createMap(GpuDevice device, int size) {
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
		if (mapFormat == GpuFormat.D16_UNORM) {
			try {
				return device.createTexture("Etna shadow map", usage, GpuFormat.D16_UNORM, size, size, 1, 1);
			} catch (RuntimeException e) {
				EtnaClient.LOGGER.warn("Etna: could not make a 16-bit shadow map, using a 32-bit one", e);
				mapFormat = GpuFormat.D32_FLOAT;
				Arrays.fill(cutoutShadowPipelines, null);
				Arrays.fill(solidShadowPipelines, null);
				Arrays.fill(solidShadowFailed, false);
			}
		}
		return device.createTexture("Etna shadow map", usage, GpuFormat.D32_FLOAT, size, size, 1, 1);
	}

	private static void writeUbo(Vector3f light, float strength, int size, boolean drawn, boolean hasSun, boolean moon) {
		ClientLevel level = Minecraft.getInstance().level;
		CardinalLighting faces = level != null ? level.cardinalLighting() : CardinalLighting.DEFAULT;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder builder = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(drawn ? primaryMatrix : NO_MAP)
				.putVec4(new Vector4f(light.x, light.y, light.z, strength))
				.putVec4(new Vector4f(1.0F / size, EtnaConfig.shadowDistance, 1.0F / (2.0F * DEPTH_RANGE), shadowReach(size)))
				.putVec4(new Vector4f(EtnaConfig.shadowSoftness.value, EtnaConfig.shadowDarknessShown(), EtnaConfig.sunlight.value, EtnaConfig.haze.value))
				.putVec4(new Vector4f(EtnaConfig.waterReflections.value, EtnaConfig.warmth.value, EtnaFrame.frame() % 4096, drawn ? 1.0F : 0.0F))
				.putVec4(new Vector4f(faces.down(), faces.up(), 0.5F * (faces.north() + faces.south()), 0.5F * (faces.west() + faces.east())))
				.putVec4(new Vector4f(hasSun ? 1.0F : 0.0F, EtnaFrame.temporal() ? 1.0F : 0.0F, moon ? 1.0F : 0.0F, rain))
				.putVec4(new Vector4f(EtnaConfig.surfaceDetail.value, EtnaConfig.shine.value, EtnaConfig.leafGlow.value, EtnaConfig.torchWarmth.value));
			// Bytes 176-239.
			EtnaWater.writeTerrain(builder);
			// Bytes 240-255.
			EtnaDark.writeTerrain(builder);
			// Bytes 256-287.
			EtnaTouch.writeTerrain(builder);
			// Bytes 288-431: the sky model's light for the terrain, with the cloud shadows' EtnaCloud at 320.
			EtnaSky.writeTerrain(builder);
			// Bytes 432-511: the second map, while the two fade; EtnaShadowFade.yz carry the Nether's lava light
			// from below (0.8.0 WEATHER, EtnaAir).
			float toSecond = drawn ? secondaryWeight : 0.0F;
			builder.putMat4f(toSecond > 0.0F ? secondaryMatrix : drawn ? primaryMatrix : NO_MAP)
				.putVec4(new Vector4f(toSecond, EtnaAir.lavaLight(), EtnaAir.lavaSurface(), 0.0F));
			// Bytes 512-575 (0.8.0): EtnaSurface and EtnaSurface2 (materials, wet ground, sparkles), EtnaNight
			// (aurora and the night sky's light on the land), EtnaWaterSsr (water reflections).
			EtnaMaterials.writeTerrain(builder);
			EtnaSky.writeNight(builder);
			EtnaWaterSsr.writeTerrain(builder);
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(ubo.slice(), builder.get());
		}
	}

	/**
	 * How far round the camera, on each axis, the map always reaches, as a share of its radius (EtnaShadowInfo.w):
	 * its middle is up to middleLeadMax blocks from the camera. The terrain fades the shadows out towards this
	 * (etnaMapOut in terrain_shadow.glsl), counted from the camera, so they end at the same distance whichever
	 * step the middle is at.
	 */
	private static float shadowReach(int size) {
		float radius = Math.max(EtnaConfig.shadowDistance, 1.0F);
		return Math.max(1.0F - middleLeadMax(radius, size) / radius, 0.5F);
	}

	/**
	 * The block as this frame wrote it, for the lit clouds (EtnaClouds), or null while it is not ready: Etna
	 * off, or the terrain lighting broken.
	 */
	static @Nullable GpuBuffer lightBlock() {
		return uboReady ? ubo : null;
	}

	/**
	 * The terrain block, for the shafts pass (PostPassMixin): the same buffer the terrain draws read, written once
	 * per frame by prepareFrame. Made here if a post chain loads before the first frame; the pass reads it only
	 * once shaftRadius says this frame wrote it.
	 */
	public static GpuBuffer postBlock() {
		if (ubo == null) {
			ubo = RenderSystem.getDevice().createBuffer(() -> "Etna shadow UBO", UBO_USAGE, UBO_SIZE);
		}
		return ubo;
	}

	/**
	 * The shadow map's radius in blocks if it was drawn this frame and the block written with it, which is when
	 * the shafts pass can march it; 0 otherwise (shadows off or failed, night's darkest moments, no sun).
	 */
	public static float shaftRadius() {
		return shaftRadius;
	}

	/** Direction towards the sun (below the horizon at night), as of this frame. */
	public static Vector3f sunDirection() {
		return sunDirection;
	}

	/** Strength of the light casting shadows right now: sun by day, a fainter moon at night, less in rain. */
	public static float lightStrength() {
		return lightStrength;
	}

	public static float rain() {
		return rain;
	}

	public static boolean moonLight() {
		return moonLight;
	}

	/** Whether this dimension has a sun at all (not the Nether or the End). */
	public static boolean sunWorld() {
		return sunWorld;
	}

	/** Whether Etna's terrain lighting is off for this session, so the terrain is drawn with vanilla's shaders. */
	static boolean terrainBroken() {
		return broken;
	}

	private static void disable(String why, @Nullable Throwable cause) {
		failed = true;
		if (cause != null) {
			EtnaClient.LOGGER.error("Etna: {}, shadows are off for this session", why, cause);
		} else {
			EtnaClient.LOGGER.error("Etna: {}, shadows are off for this session", why);
		}
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
