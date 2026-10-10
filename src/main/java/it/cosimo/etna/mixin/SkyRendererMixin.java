package it.cosimo.etna.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import it.cosimo.etna.EtnaSky;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where Etna's sky (EtnaSky) takes the place of vanilla's.
 *
 * SkyRenderer.prepare runs right before the sky's render pass opens (in the sky pass with improved
 * transparency, before the main pass without it): EtnaSky uploads its block there, as nothing may be
 * written to a buffer while a pass is open. SkyRenderer.render then draws, in the overworld, the sky disc,
 * the sunrise band, the sun, the moon and the stars, and the occluder below the horizon. While EtnaSky
 * replaces vanilla's sky, Etna's whole sky goes in the place of the disc, the band, the sun and the stars
 * stand down, the moon stands down too while Etna draws its round moon (Round Moon), and the occluder
 * draws over it as usual. The sun path's tilt is applied to the pose the sun, the moon and the stars are
 * turned by, whether Etna's sky or vanilla's is drawn. In the End, SkyRenderer.render draws the End's sky
 * box and its flash: while EtnaSky replaces them, Etna's End sky goes in the place of the box and the
 * flash (which it draws itself) stands down.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	@Inject(method = "prepare", at = @At("RETURN"))
	private void etna$prepare(SkyRenderState state, CallbackInfo ci) {
		EtnaSky.prepare();
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void etna$beginRender(SkyRenderState state, RenderPass renderPass, Vector4f fogColor, boolean withDepthAttachment, CallbackInfo ci) {
		EtnaSky.beginRender(state);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void etna$endRender(SkyRenderState state, RenderPass renderPass, Vector4f fogColor, boolean withDepthAttachment, CallbackInfo ci) {
		EtnaSky.endRender();
	}

	@Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
	private void etna$sky(RenderPass renderPass, Vector3fc skyColor, boolean withDepthAttachment, CallbackInfo ci) {
		if (EtnaSky.drawSky(renderPass, withDepthAttachment)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
	private void etna$sunriseAndSunset(
		RenderPass renderPass,
		PoseStack poseStack,
		float sunAngle,
		Vector4fc sunriseAndSunsetColor,
		boolean withDepthAttachment,
		CallbackInfo ci
	) {
		if (EtnaSky.replacing()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderSunMoonAndStars", at = @At("HEAD"))
	private void etna$tilt(
		RenderPass renderPass,
		PoseStack poseStack,
		float sunAngle,
		float moonAngle,
		float starAngle,
		MoonPhase moonPhase,
		float rainBrightness,
		float starBrightness,
		boolean withDepthAttachment,
		CallbackInfo ci
	) {
		EtnaSky.tiltPose(poseStack);
	}

	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void etna$sun(RenderPass renderPass, float rainBrightness, PoseStack poseStack, boolean withDepthAttachment, CallbackInfo ci) {
		if (EtnaSky.replacing()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void etna$moon(
		RenderPass renderPass,
		MoonPhase moonPhase,
		float rainBrightness,
		PoseStack poseStack,
		boolean withDepthAttachment,
		CallbackInfo ci
	) {
		if (EtnaSky.drawsMoon()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true)
	private void etna$endSky(RenderPass renderPass, boolean withDepthAttachment, CallbackInfo ci) {
		if (EtnaSky.drawEndSky(renderPass, withDepthAttachment)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderEndFlash", at = @At("HEAD"), cancellable = true)
	private void etna$endFlash(
		RenderPass renderPass,
		PoseStack poseStack,
		float intensity,
		float xAngle,
		float yAngle,
		boolean withDepthAttachment,
		CallbackInfo ci
	) {
		if (EtnaSky.replacingEnd()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
	private void etna$stars(RenderPass renderPass, float starBrightness, PoseStack poseStack, boolean withDepthAttachment, CallbackInfo ci) {
		if (EtnaSky.replacing()) {
			ci.cancel();
		}
	}
}
