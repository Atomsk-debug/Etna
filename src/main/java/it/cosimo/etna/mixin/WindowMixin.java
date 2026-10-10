package it.cosimo.etna.mixin;

import com.mojang.blaze3d.platform.MacosUtil;
import com.mojang.blaze3d.platform.Window;
import it.cosimo.etna.EtnaClient;
import it.cosimo.etna.EtnaConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Etna's "half resolution" for Macs: Minecraft opens its window with SDL_WINDOW_HIGH_PIXEL_DENSITY, so a
 * Retina screen renders every frame at twice the width and height. Without the flag the game draws a
 * quarter of the pixels and macOS scales the image up, like running a game at a lower resolution. Only while
 * Etna is on when the game starts: with Etna off the window is vanilla's (the flag can't change once the
 * window is open, so turning Etna on or off with F7 takes effect at the next start).
 */
@Mixin(Window.class)
public abstract class WindowMixin {
	private static final long SDL_WINDOW_HIGH_PIXEL_DENSITY = 0x2000L;

	@ModifyArg(method = "createWindow",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow"),
		index = 3, require = 0)
	private long etna$halfResolution(long flags) {
		EtnaConfig.ensureLoaded();
		if (MacosUtil.IS_MACOS && EtnaConfig.halfResolution && EtnaConfig.enabled) {
			EtnaClient.LOGGER.info("Etna: half resolution, opening the window without Retina scaling");
			return flags & ~SDL_WINDOW_HIGH_PIXEL_DENSITY;
		}
		return flags;
	}
}
