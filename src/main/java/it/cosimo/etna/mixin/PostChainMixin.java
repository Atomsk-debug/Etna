package it.cosimo.etna.mixin;

import java.util.Optional;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import it.cosimo.etna.EtnaClient;
import it.cosimo.etna.EtnaFrame;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Post chain targets are always 8 bits per channel. Etna's own targets become 16-bit floats instead:
 * bloom can hold light brighter than white without banding, and the anti-aliasing history keeps
 * enough precision to blend frames for a long time (and each pixel's age in alpha). The one exception
 * is "graded", the colour grade's result: it is already clamped and dithered to 8 bits for Minecraft's
 * 8-bit picture, which is all that reads it. Every pass that writes into a 16-bit target is built for
 * that format; passes that write into an 8-bit one keep vanilla's. Etna's targets also get no depth
 * texture (no Etna pass uses one), and fixed-size targets (the bloom levels) are sized for a 1920x1080
 * screen and scaled to the real one, width with width and height with height: the first bloom level is
 * always half the screen, so every pixel counts and the glow stays round and the same size on any
 * screen shape.
 *
 * Vanilla clears every target a chain takes for the frame, because a pass may draw over only part of
 * it. Every Etna pass draws every pixel of its target, so Etna's frame targets are taken as they are
 * (PostPassMixin clears them on the GPU's tiles as each pass opens instead, which is free on Apple's
 * GPUs). The persistent targets still start cleared when they are made: the anti-aliasing history must
 * never begin as undefined memory, since a NaN in it would never blend away.
 *
 * The anti-aliasing history is two persistent targets used in turn: the world chain reads last frame's
 * result from "history0" and writes this frame's into "history1", and the two textures behind those
 * names swap after every frame (EtnaFrame.historySwapped), so the result is never copied. The water
 * reflections keep their own history in "water_ssr0" and "water_ssr1" the same way.
 * Vanilla's chains are untouched.
 */
@Mixin(PostChain.class)
public abstract class PostChainMixin {
	@Unique
	private static final ColorTargetState ETNA_HDR_TARGET = new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, 15);

	/** The colour grade's result; gen_post_effects.py knows this name. */
	@Unique
	private static final Identifier ETNA_GRADED = Identifier.withDefaultNamespace("graded");

	/** The name the frame gives the target, as PostChain.addToFrame writes it. */
	@Unique
	private static final String ETNA_GRADED_NAME = ETNA_GRADED.toString();

	/** The two anti-aliasing histories; gen_post_effects.py knows these names. */
	@Unique
	private static final Identifier ETNA_HISTORY_READ = Identifier.withDefaultNamespace("history0");

	@Unique
	private static final Identifier ETNA_HISTORY_WRITE = Identifier.withDefaultNamespace("history1");

	/** The water reflections' own history, kept the same way; gen_post_effects.py knows these names. */
	@Unique
	private static final Identifier ETNA_SSR_READ = Identifier.withDefaultNamespace("water_ssr0");

	@Unique
	private static final Identifier ETNA_SSR_WRITE = Identifier.withDefaultNamespace("water_ssr1");

	@Unique
	private static boolean etna$hdrPass;

	@Shadow
	@Final
	private Identifier id;

	@Unique
	private int etna$screenWidth = 1;

	@Unique
	private int etna$screenHeight = 1;

	@Inject(method = "createPass", at = @At("HEAD"))
	private static void etna$checkPass(TextureManager textureManager, PostChainConfig.Pass config, Identifier passId,
			CallbackInfoReturnable<PostPass> cir) {
		Identifier output = config.outputTarget();
		etna$hdrPass = passId.getNamespace().equals(EtnaClient.MOD_ID) && !output.equals(PostChain.MAIN_TARGET_ID)
			&& !output.equals(ETNA_GRADED);
	}

	@ModifyArg(
		method = "createPass",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withColorTargetState(Lcom/mojang/renderpearl/api/pipeline/ColorTargetState;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"
		)
	)
	private static ColorTargetState etna$hdrPassFormat(ColorTargetState state) {
		return etna$hdrPass ? ETNA_HDR_TARGET : state;
	}

	@ModifyArg(
		method = "addToFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;<init>(Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/GpuFormat;)V"
		),
		index = 1
	)
	private GpuFormat etna$hdrTargets(GpuFormat format) {
		return this.id.getNamespace().equals(EtnaClient.MOD_ID) ? GpuFormat.RGBA16_FLOAT : format;
	}

	@Inject(method = "addToFrame", at = @At("HEAD"))
	private void etna$screenSize(FrameGraphBuilder frame, int screenWidth, int screenHeight, PostChain.TargetBundle providedTargets,
			CallbackInfo ci) {
		etna$screenWidth = Math.max(screenWidth, 1);
		etna$screenHeight = Math.max(screenHeight, 1);
	}

	// Each handler only reads and changes its own argument: Mixin runs handlers on the same call one
	// after the other, so a handler that also read the width would see it already scaled.

	@ModifyArg(
		method = "addToFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IILcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;Lcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;)V"
		),
		index = 0
	)
	private int etna$targetWidth(int width) {
		if (!this.id.getNamespace().equals(EtnaClient.MOD_ID) || width == etna$screenWidth) {
			return width;
		}
		return Math.max(1, Math.round(width * etna$screenWidth / 1920.0F));
	}

	@ModifyArg(
		method = "addToFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IILcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;Lcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;)V"
		),
		index = 1
	)
	private int etna$targetHeight(int height) {
		if (!this.id.getNamespace().equals(EtnaClient.MOD_ID) || height == etna$screenHeight) {
			return height;
		}
		return Math.max(1, Math.round(height * etna$screenHeight / 1080.0F));
	}

	@ModifyArg(
		method = "addToFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;<init>(IILcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;Lcom/mojang/blaze3d/resource/RenderTargetDescriptor$TextureProperties;)V"
		),
		index = 3
	)
	private RenderTargetDescriptor.@Nullable TextureProperties etna$noDepth(RenderTargetDescriptor.@Nullable TextureProperties depth) {
		return this.id.getNamespace().equals(EtnaClient.MOD_ID) ? null : depth;
	}

	/**
	 * The frame targets (not the persistent ones, which never pass through here): no clear colour, so the
	 * frame takes them without clearing them, and the 8-bit format for the colour grade's result. The frame's
	 * pool hands a texture back only to a target with the same size, format and clear colour, so these never
	 * mix with vanilla's cleared targets.
	 */
	@ModifyArg(
		method = "addToFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;createInternal(Ljava/lang/String;Lcom/mojang/blaze3d/resource/ResourceDescriptor;)Lcom/mojang/blaze3d/resource/ResourceHandle;"
		),
		index = 1
	)
	private ResourceDescriptor<?> etna$unclearedTargets(String name, ResourceDescriptor<?> descriptor) {
		if (!this.id.getNamespace().equals(EtnaClient.MOD_ID) || !(descriptor instanceof RenderTargetDescriptor target)) {
			return descriptor;
		}
		RenderTargetDescriptor.TextureProperties color = target.color();
		if (color == null) {
			return descriptor;
		}
		GpuFormat format = name.equals(ETNA_GRADED_NAME) ? GpuFormat.RGBA8_UNORM : color.format();
		return new RenderTargetDescriptor(target.width(), target.height(), new RenderTargetDescriptor.TextureProperties(null, format),
			target.depth());
	}

	/**
	 * Every other frame the world chain's two history names are given each other's textures: the pass
	 * that reads "history0" then reads what the last frame wrote into "history1", and the same for the water
	 * reflections' "water_ssr0" and "water_ssr1". Only the lookup changes; each texture is still made,
	 * resized and freed with the chain's other persistent targets.
	 */
	@ModifyArg(
		method = "addToFrame",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/PostChain;getOrCreatePersistentTarget(Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/resource/RenderTargetDescriptor;)Lcom/mojang/blaze3d/pipeline/RenderTarget;"
		),
		index = 0
	)
	private Identifier etna$historyInTurn(Identifier target) {
		if (!EtnaFrame.historySwapped() || !this.id.equals(EtnaFrame.WORLD)) {
			return target;
		}
		if (target.equals(ETNA_HISTORY_READ)) {
			return ETNA_HISTORY_WRITE;
		}
		if (target.equals(ETNA_HISTORY_WRITE)) {
			return ETNA_HISTORY_READ;
		}
		if (target.equals(ETNA_SSR_READ)) {
			return ETNA_SSR_WRITE;
		}
		return target.equals(ETNA_SSR_WRITE) ? ETNA_SSR_READ : target;
	}
}
