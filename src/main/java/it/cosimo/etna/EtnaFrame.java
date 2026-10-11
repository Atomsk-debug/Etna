package it.cosimo.etna;

import java.lang.ref.WeakReference;
import java.util.Set;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Per-frame camera bookkeeping for the passes that look back at earlier frames, and the world pass.
 *
 * Temporal anti-aliasing moves the level's projection by a different fraction of a pixel every frame
 * (a Halton sequence of 8 steps, more with the world drawn smaller), so over a few frames every pixel sees
 * its whole area. The world post chain (light shafts, ambient occlusion, the air and the mist, and anti-aliasing) then runs right after
 * the level, before the hand is drawn: the hand is neither jittered nor blended with old frames, so it
 * never smears.
 *
 * With anti-aliasing off and shadows on, the plain world chain runs in its place: the same light shafts,
 * sky glow, atmosphere and mist, without ambient occlusion (its noise needs the anti-aliasing) and without
 * the anti-aliasing. The light shafts used to be drawn by the terrain shader and so never needed the world
 * pass; now they do, and the plain chain keeps them on wherever 0.6.0 had them. With both off (Low
 * quality) no world chain runs, as before.
 */
public final class EtnaFrame {
	public static final Identifier WORLD = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "etna_world");
	public static final Identifier WORLD_PLAIN = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "etna_world_plain");
	/**
	 * The world chain for a world drawn smaller than the screen (EtnaScale): the same passes on the smaller picture,
	 * with the anti-aliasing's history at full size and its last pass writing Minecraft's own picture, "etna:screen".
	 */
	public static final Identifier WORLD_SCALED = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "etna_world_scaled");
	private static final Set<Identifier> SCALED_TARGETS = Set.of(PostChain.MAIN_TARGET_ID, EtnaScale.SCREEN);

	/**
	 * The jitter is Halton(2, 3) over these steps at full size, step = frame % steps + 1 (jitterSteps). The
	 * terrain reads this frame's jitter from its block (EtnaSurface2.zw, EtnaMaterials) to hold the water's glints
	 * still.
	 */
	private static final int JITTER_STEPS = 8;
	/** The most steps with the world drawn smaller (from half its width down; see jitterSteps). */
	private static final int JITTER_STEPS_MAX = 32;
	/** A camera jump longer than this (teleport, respawn) starts the history over. */
	private static final double MAX_CAMERA_STEP = 16.0;

	private static final Matrix4f viewProjection = new Matrix4f();
	private static final Matrix4f previousViewProjection = new Matrix4f();
	private static final Matrix4f screenFromNdc = new Matrix4f();
	private static final Matrix4f ndcFromScreen = new Matrix4f();

	private static long frame;
	private static long lastWorldFrame = -10;
	private static boolean temporal;
	private static boolean historyValid;
	private static float jitterX;
	private static float jitterY;
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;
	private static float motionX;
	private static float motionY;
	private static float motionZ;
	private static int width;
	private static int height;
	/** The screen's size that frame (EtnaScale): the history is at that size. */
	private static int screenWidth;
	private static int screenHeight;
	/** The level of the last frame drawn, held weakly: after leaving a world the menus must not keep it alive. */
	private static WeakReference<@Nullable ClientLevel> level = new WeakReference<>(null);
	private static boolean worldFailed;
	private static boolean plainFailed;
	private static boolean historyHeld;
	/** Which world chain holds the anti-aliasing history: the scaled one or the full-size one. */
	private static boolean historyScaled;
	/**
	 * The anti-aliasing history is two textures used in turn (PostChainMixin): when this is set, last
	 * frame's result is in the one named "history1", so the names are swapped for this frame.
	 */
	private static boolean historySwapped;
	private static long outlineFrame = -10;

	private EtnaFrame() {
	}

	/**
	 * GameRenderer.renderLevel, as it uploads the level projection: remembers this frame's camera and
	 * returns the projection to draw the level with (moved by this frame's jitter when anti-aliasing runs).
	 */
	public static Matrix4f beginLevel(Matrix4f projection, CameraRenderState camera, int targetWidth, int targetHeight) {
		frame++;
		Minecraft mc = Minecraft.getInstance();
		temporal = EtnaClient.activeEffect() != null && EtnaConfig.temporalActive() && !worldFailed;

		previousViewProjection.set(viewProjection);
		viewProjection.set(projection).mul(camera.viewRotationMatrix);

		Vec3 pos = camera.pos;
		double dx = pos.x - cameraX;
		double dy = pos.y - cameraY;
		double dz = pos.z - cameraZ;
		ClientLevel current = mc.level;
		boolean sameLevel = current == level.get();
		boolean sameView = lastWorldFrame == frame - 1 && targetWidth == width && targetHeight == height
			&& EtnaScale.screenWidth() == screenWidth && EtnaScale.screenHeight() == screenHeight && sameLevel
			&& dx * dx + dy * dy + dz * dz < MAX_CAMERA_STEP * MAX_CAMERA_STEP;
		historyValid = temporal && sameView;
		motionX = (float) dx;
		motionY = (float) dy;
		motionZ = (float) dz;
		cameraX = pos.x;
		cameraY = pos.y;
		cameraZ = pos.z;
		width = targetWidth;
		height = targetHeight;
		screenWidth = EtnaScale.screenWidth();
		screenHeight = EtnaScale.screenHeight();
		if (!sameLevel) {
			level = new WeakReference<>(current);
		}

		// Screen space as the post passes see it: uv in 0..1 and the depth buffer's own value.
		boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		screenFromNdc.translation(0.5F, 0.5F, zeroToOne ? 0.0F : 0.5F).scale(0.5F, 0.5F, zeroToOne ? 1.0F : 0.5F);
		screenFromNdc.invert(ndcFromScreen);

		if (!temporal) {
			jitterX = 0.0F;
			jitterY = 0.0F;
			return projection;
		}
		int step = (int) (frame % jitterSteps(targetWidth)) + 1;
		jitterX = halton(step, 2) - 0.5F;
		jitterY = halton(step, 3) - 0.5F;
		// Shift in clip space: x' = x + offset * w moves the whole picture by the offset in NDC.
		return new Matrix4f().translation(2.0F * jitterX / targetWidth, 2.0F * jitterY / targetHeight, 0.0F).mul(projection);
	}

	/**
	 * GameRenderer.renderLevel, right before the hand: the world chain (light shafts, ambient occlusion, the air
	 * and anti-aliasing), or the plain one without anti-aliasing. The outlines
	 * of glowing entities are drawn with the jittered projection too, so they are blended in first and
	 * steadied with the rest of the world; vanilla's own blend after the level is then skipped
	 * (see {@link #outlineDone()}).
	 */
	public static void processWorld(RenderTarget mainTarget, GraphicsResourceAllocator allocator) {
		Minecraft mc = Minecraft.getInstance();
		if (!temporal) {
			// Anti-aliasing (or Etna) was turned off: give back the screen-size history it kept.
			if (historyHeld) {
				releaseHistory(mc);
			}
			if (EtnaClient.activeEffect() != null && EtnaConfig.worldPassActive() && !plainFailed) {
				processPlain(mc, mainTarget, allocator);
			}
			return;
		}
		RenderTarget scaled = EtnaScale.scaledTarget();
		if (historyHeld && historyScaled != (scaled != null)) {
			// The world changed size: the other chain's history no longer fits, and beginLevel already started over.
			releaseHistory(mc);
		}
		if (scaled != null) {
			processScaled(mc, mainTarget, scaled, allocator);
			return;
		}
		PostChain chain = mc.getShaderManager().getPostChain(WORLD, LevelTargetBundle.MAIN_TARGETS);
		if (chain == null) {
			fail("the world pass did not load", null);
			return;
		}
		mc.levelRenderer.blitEntityOutline();
		outlineFrame = frame;
		try {
			chain.process(mainTarget, allocator);
			worldDone(false);
		} catch (RuntimeException e) {
			fail("the world pass failed", e);
		}
	}

	/**
	 * The world chain on a world drawn smaller than the screen (EtnaScale): its passes run on the smaller picture,
	 * and the anti-aliasing builds the full-size one into Minecraft's own (its last pass writes "etna:screen").
	 * The entity outlines were drawn at the smaller size too, so they are blended in first, as on the full-size path.
	 */
	private static void processScaled(Minecraft mc, RenderTarget screen, RenderTarget scaled, GraphicsResourceAllocator allocator) {
		PostChain chain = mc.getShaderManager().getPostChain(WORLD_SCALED, SCALED_TARGETS);
		if (chain == null) {
			// Without it nothing builds the full-size picture; the next frame draws the world at full size.
			EtnaScale.fail("the scaled world pass did not load", null);
			EtnaScale.clearScreen(screen);
			return;
		}
		mc.levelRenderer.blitEntityOutline();
		outlineFrame = frame;
		try {
			FrameGraphBuilder graph = new FrameGraphBuilder();
			ScaledTargets targets = new ScaledTargets(graph.importExternal("main", scaled), graph.importExternal("screen", screen));
			chain.addToFrame(graph, scaled.width, scaled.height, targets);
			graph.execute(allocator);
			worldDone(true);
		} catch (RuntimeException e) {
			EtnaScale.fail("the scaled world pass failed", e);
			EtnaScale.clearScreen(screen);
		}
	}

	private static void worldDone(boolean scaled) {
		lastWorldFrame = frame;
		historyHeld = true;
		historyScaled = scaled;
		// This frame's result went into the texture the next frame must read as its history.
		historySwapped = !historySwapped;
	}

	/** Gives back the screen-size history the anti-aliasing kept, in whichever world chain holds it. */
	private static void releaseHistory(Minecraft mc) {
		historyHeld = false;
		historySwapped = false;
		PostChain chain = historyScaled ? mc.getShaderManager().getPostChain(WORLD_SCALED, SCALED_TARGETS)
			: mc.getShaderManager().getPostChain(WORLD, LevelTargetBundle.MAIN_TARGETS);
		if (chain != null) {
			chain.closePersistentTargets();
		}
	}

	/** Minecraft's picture under its two names in the scaled world chain: the smaller one as main, the screen's own. */
	private static final class ScaledTargets implements PostChain.TargetBundle {
		private ResourceHandle<RenderTarget> main;
		private ResourceHandle<RenderTarget> screen;

		ScaledTargets(ResourceHandle<RenderTarget> main, ResourceHandle<RenderTarget> screen) {
			this.main = main;
			this.screen = screen;
		}

		@Override
		public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
			if (id.equals(PostChain.MAIN_TARGET_ID)) {
				this.main = handle;
			} else if (id.equals(EtnaScale.SCREEN)) {
				this.screen = handle;
			} else {
				throw new IllegalArgumentException("No target with id " + id);
			}
		}

		@Override
		public @Nullable ResourceHandle<RenderTarget> get(Identifier id) {
			if (id.equals(PostChain.MAIN_TARGET_ID)) {
				return this.main;
			}
			return id.equals(EtnaScale.SCREEN) ? this.screen : null;
		}
	}

	/**
	 * The plain world chain, without anti-aliasing. The entity outlines stay vanilla's to blend after it: nothing
	 * is jittered, so they already line up.
	 */
	private static void processPlain(Minecraft mc, RenderTarget mainTarget, GraphicsResourceAllocator allocator) {
		PostChain chain = mc.getShaderManager().getPostChain(WORLD_PLAIN, LevelTargetBundle.MAIN_TARGETS);
		if (chain == null) {
			failPlain("the plain world pass did not load", null);
			return;
		}
		try {
			chain.process(mainTarget, allocator);
		} catch (RuntimeException e) {
			failPlain("the plain world pass failed", e);
		}
	}

	private static void failPlain(String why, @Nullable Throwable cause) {
		plainFailed = true;
		if (cause != null) {
			EtnaClient.LOGGER.error("Etna: {}, light shafts and mist are off without anti-aliasing for this session", why, cause);
		} else {
			EtnaClient.LOGGER.error("Etna: {}, light shafts and mist are off without anti-aliasing for this session", why);
		}
	}

	/** True when this frame's entity outlines were already blended in before anti-aliasing. */
	public static boolean outlineDone() {
		return outlineFrame == frame;
	}

	/**
	 * Resources were reloaded: the post chains were rebuilt and the shaders recompiled, so a world pass, a sky
	 * pipeline or a lit cloud pipeline that failed to compile gets another try.
	 */
	public static void resourcesReloaded() {
		worldFailed = false;
		plainFailed = false;
		historyHeld = false;
		EtnaScale.resourcesReloaded();
		EtnaSky.resourcesReloaded();
		EtnaClouds.resourcesReloaded();
		EtnaWeather.resourcesReloaded();
		EtnaShadows.resourcesReloaded();
	}

	private static void fail(String why, @Nullable Throwable cause) {
		worldFailed = true;
		temporal = false;
		if (cause != null) {
			EtnaClient.LOGGER.error("Etna: {}, anti-aliasing and ambient occlusion are off for this session", why, cause);
		} else {
			EtnaClient.LOGGER.error("Etna: {}, anti-aliasing and ambient occlusion are off for this session", why);
		}
	}

	public static boolean temporal() {
		return temporal;
	}

	/** False once the anti-aliased world chain has failed this session: nothing could build a smaller world back up. */
	public static boolean worldPassWorks() {
		return !worldFailed;
	}

	public static long frame() {
		return frame;
	}

	/** Screen (uv, depth) to camera-relative position, this frame, without jitter. */
	public static Matrix4f inverseViewProjection(Matrix4f dest) {
		return viewProjection.invert(dest).mul(ndcFromScreen);
	}

	/** Camera-relative position to screen (uv, depth), this frame. */
	public static Matrix4f viewProjection(Matrix4f dest) {
		return dest.set(screenFromNdc).mul(viewProjection);
	}

	/** Position relative to last frame's camera to last frame's screen (uv, depth). */
	public static Matrix4f previousViewProjection(Matrix4f dest) {
		return dest.set(screenFromNdc).mul(previousViewProjection);
	}

	public static float motionX() {
		return motionX;
	}

	public static float motionY() {
		return motionY;
	}

	public static float motionZ() {
		return motionZ;
	}

	public static boolean historyValid() {
		return historyValid;
	}

	/** True when this frame's world chain reads its history from "history1" and writes "history0". */
	public static boolean historySwapped() {
		return historySwapped;
	}

	public static float jitterX() {
		return jitterX;
	}

	public static float jitterY() {
		return jitterY;
	}

	/**
	 * Jitter steps for a level drawn targetWidth pixels wide. With the world drawn smaller than the screen
	 * (EtnaScale) each step's samples are spread over fewer level pixels than there are screen pixels, so it takes
	 * more steps for every screen pixel to be seen about as often as at full size: 8 / scale^2 (the camera jitter
	 * guidance of AMD's FidelityFX Super Resolution 2), rounded up to a power of two so frame % 4096 stays a
	 * whole number of cycles. 16 at 75%, 32 at half size.
	 */
	private static int jitterSteps(int targetWidth) {
		float scale = (float) targetWidth / Math.max(EtnaScale.screenWidth(), 1);
		if (scale >= 0.999F) {
			return JITTER_STEPS;
		}
		int wanted = (int) Math.ceil(JITTER_STEPS / (scale * scale));
		return Math.min(Integer.highestOneBit(wanted - 1) << 1, JITTER_STEPS_MAX);
	}

	/** Halton's radical inverse of index in base. */
	private static float halton(int index, int base) {
		float f = 1.0F;
		float result = 0.0F;
		while (index > 0) {
			f /= base;
			result += f * (index % base);
			index /= base;
		}
		return result;
	}
}
