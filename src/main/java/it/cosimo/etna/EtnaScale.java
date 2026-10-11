package it.cosimo.etna;

import java.util.Map;

import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Render scale: the world drawn at fewer pixels than the screen has, and built back up to full size by Etna's
 * anti-aliasing (temporal upsampling, post/taa.fsh). Everything Etna adds to the terrain is worked out per pixel,
 * and so is most of vanilla's own drawing, so at 0.75 of the width and height the level pass shades a little over
 * half the pixels. The camera's sub-pixel jitter (EtnaFrame) moves in the smaller picture's pixels, so over a few
 * frames the anti-aliasing still sees every part of each screen pixel.
 *
 * While the level is drawn, GameRenderer.mainRenderTarget() hands out a smaller target of Etna's own
 * (GameRendererMixin), so LevelRenderer builds its whole frame on it, from the sky and the terrain to the
 * particles, the weather and the translucent layers, and every size it derives comes out the same. The world
 * chain then runs on that target and its last pass writes the full-size picture into Minecraft's own (EtnaFrame,
 * the "etna:screen" target). The hand, the screen effects and the HUD are drawn after that, at full size and never
 * jittered. The final chain's passes that read main's depth (the bloom's distance) get the level's depth from the
 * smaller target instead (LEVEL_DEPTH): main's own depth holds only the hand then.
 *
 * Only with Etna's anti-aliasing running: without it nothing would build the full-size picture.
 */
public final class EtnaScale {
	/** The world chain's own name for Minecraft's full-size picture while the world is drawn smaller. */
	public static final Identifier SCREEN = Identifier.fromNamespaceAndPath(EtnaClient.MOD_ID, "screen");
	/**
	 * A target in an Etna chain this many texels wide or tall is made the size of the full screen (PostChainMixin):
	 * the anti-aliasing history, which the world chain keeps at full size while it runs on the smaller picture.
	 */
	public static final int SCREEN_SIZED = 16384;

	/**
	 * A target in an Etna chain whose width or height is this much more than a number is that number of texels
	 * for a 1920x1080 screen, scaled to the size the chain runs at (PostChainMixin): the bloom levels, and the half-size
	 * light shafts and water reflections. A target with no size at all is the chain's own size, as in vanilla.
	 */
	public static final int REFERENCE_SIZED = 20000;

	/** The bloom's input for main's own depth, which LevelDepth leaves alone (gen_post_effects.py knows this name). */
	public static final String HAND_DEPTH = "HandDepth";

	private static final Vector4f BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);

	private static @Nullable TextureTarget target;
	/** This frame's level is being drawn into the smaller target. */
	private static boolean drawing;
	/** This frame's world was drawn smaller (from the start of the level to the end of the frame). */
	private static boolean scaledFrame;
	private static int screenWidth = 1;
	private static int screenHeight = 1;
	private static int levelWidth = 1;
	private static int levelHeight = 1;
	private static boolean failed;

	private EtnaScale() {
	}

	/** The scale the settings ask for, clamped; 1 when it can't apply (Etna or its anti-aliasing off). */
	public static float wanted() {
		if (failed || !EtnaConfig.temporalActive() || EtnaClient.activeEffect() == null || !EtnaFrame.worldPassWorks()) {
			return 1.0F;
		}
		return Math.clamp(EtnaConfig.renderScale, EtnaConfig.RENDER_SCALE_MIN, 1.0F);
	}

	/**
	 * GameRenderer.renderLevel, before anything of the level is drawn: decides this frame's size and gets the
	 * smaller target ready (made or resized), or frees it when the world is drawn at full size again.
	 * LevelRenderer clears the smaller target itself, as it clears whichever target mainRenderTarget() gives it.
	 */
	public static void beginLevel(RenderTarget main) {
		screenWidth = Math.max(main.width, 1);
		screenHeight = Math.max(main.height, 1);
		levelWidth = screenWidth;
		levelHeight = screenHeight;
		drawing = false;
		scaledFrame = false;
		float scale = wanted();
		int width = Math.max(1, Math.round(screenWidth * scale));
		int height = Math.max(1, Math.round(screenHeight * scale));
		if (width >= screenWidth && height >= screenHeight) {
			release();
			return;
		}
		try {
			TextureTarget scaled = target;
			if (scaled == null) {
				// The level's pipelines are built for Minecraft's own picture: the same formats.
				GpuFormat depthFormat = main.getDepthTexture() != null ? main.getDepthTexture().getFormat() : GpuFormat.D32_FLOAT;
				scaled = new TextureTarget("Etna level", width, height, main.getColorTexture().getFormat(), depthFormat);
				target = scaled;
			} else if (scaled.width != width || scaled.height != height) {
				scaled.resize(width, height);
			}
			// Minecraft's own depth then holds only the hand, which the bloom tells apart from the level by it
			// (bloom_prefilter.fsh): clear what the last frame left there (its GUI's depth, a full-size level).
			if (main.getDepthTexture() != null) {
				RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(main.getDepthTexture(), 0.0);
			}
			levelWidth = width;
			levelHeight = height;
			drawing = true;
			scaledFrame = true;
		} catch (RuntimeException e) {
			fail("could not make the smaller target", e);
		}
	}

	/**
	 * GameRenderer.render, before anything is drawn: no frame starts with the smaller target handed out, and a
	 * frame without a level (menus, after leaving a world) frees it.
	 */
	public static void beginFrame(boolean level) {
		drawing = false;
		scaledFrame = false;
		if (!level) {
			release();
		}
	}

	/**
	 * The world chain could not build the full-size picture from the smaller one: Minecraft's own still holds the
	 * last frame's finished picture, GUI and all. Black is less wrong for the one frame before the world is drawn at
	 * full size again.
	 */
	public static void clearScreen(RenderTarget main) {
		try {
			if (main.getDepthTexture() != null) {
				RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(main.getColorTexture(), BLACK,
					main.getDepthTexture(), 0.0);
			}
		} catch (RuntimeException e) {
			EtnaClient.LOGGER.warn("Etna: could not clear the screen after the scaled world pass failed", e);
		}
	}

	/** GameRenderer.renderLevel, once the world chain has written the full-size picture: the hand comes next. */
	public static void endLevel() {
		drawing = false;
	}

	/** What GameRenderer.mainRenderTarget() returns: the smaller target while this frame's level is drawn. */
	public static RenderTarget levelTarget(RenderTarget main) {
		TextureTarget scaled = target;
		return drawing && scaled != null ? scaled : main;
	}

	/** The smaller target while it holds this frame's level, else null. */
	public static @Nullable RenderTarget scaledTarget() {
		return scaledFrame ? target : null;
	}

	public static boolean scaledFrame() {
		return scaledFrame;
	}

	/** The size the level is drawn at this frame, in pixels. */
	public static int levelWidth() {
		return levelWidth;
	}

	public static int levelHeight() {
		return levelHeight;
	}

	/** The screen's size (Minecraft's own picture), in pixels. */
	public static int screenWidth() {
		return screenWidth;
	}

	public static int screenHeight() {
		return screenHeight;
	}

	/** The world chain failed on the smaller picture: the world is drawn at full size for the rest of the session. */
	public static void fail(String why, @Nullable Throwable cause) {
		failed = true;
		drawing = false;
		scaledFrame = false;
		if (cause != null) {
			EtnaClient.LOGGER.error("Etna: render scale is off for this session, {}", why, cause);
		} else {
			EtnaClient.LOGGER.error("Etna: render scale is off for this session, {}", why);
		}
	}

	/** Resources were reloaded: the world chains were rebuilt, so a scaled chain that failed gets another try. */
	public static void resourcesReloaded() {
		failed = false;
	}

	private static void release() {
		TextureTarget scaled = target;
		if (scaled != null) {
			target = null;
			scaled.destroyBuffers();
		}
	}

	/**
	 * Stands in for a pass input that reads main's depth (PostPassMixin, Etna's passes only): on a frame whose world
	 * was drawn smaller, the level's depth from the smaller target, else main's own. The frame still orders the
	 * pass after whatever wrote main, as the input it replaces did.
	 */
	public record LevelDepth(PostPass.Input original) implements PostPass.Input {
		@Override
		public void addToPass(FramePass pass, Map<Identifier, ResourceHandle<RenderTarget>> targets) {
			this.original.addToPass(pass, targets);
		}

		@Override
		public void cleanup(Map<Identifier, ResourceHandle<RenderTarget>> targets) {
			this.original.cleanup(targets);
		}

		@Override
		public GpuTextureView texture(Map<Identifier, ResourceHandle<RenderTarget>> targets) {
			RenderTarget scaled = scaledTarget();
			if (scaled != null) {
				GpuTextureView depth = scaled.getDepthTextureView();
				if (depth != null) {
					return depth;
				}
			}
			return this.original.texture(targets);
		}

		@Override
		public String samplerName() {
			return this.original.samplerName();
		}

		@Override
		public boolean bilinear() {
			return this.original.bilinear();
		}

		/**
		 * Whether an input reads main's depth, the inputs LevelDepth stands in for. Not "HandDepth", which the bloom
		 * reads to find the hand in main's own depth.
		 */
		public static boolean readsMainDepth(PostPass.Input input) {
			return input instanceof PostPass.TargetInput target && target.depthBuffer() && target.targetId().equals(PostChain.MAIN_TARGET_ID)
				&& !target.samplerName().equals(HAND_DEPTH);
		}
	}
}
