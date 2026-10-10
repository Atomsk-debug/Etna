package it.cosimo.etna;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Etna: a shader pipeline built only on Minecraft's own render backend.
 *
 * Terrain lighting and shadows live in Etna's terrain shader (assets/etna/shaders/core), which only Etna's own
 * copies of the terrain pipelines use while Etna is on, and EtnaShadows. The screen effects are vanilla-format post chains (assets/etna/post_effect):
 * etna_world (light shafts, then ambient occlusion, sky glow, atmosphere and mist, then anti-aliasing, run by EtnaFrame before
 * the hand is drawn; etna_world_plain, without anti-aliasing, in its place when that is off) and etna
 * (bloom and the colour grade, added to the frame's post effects by GameRendererMixin). Minecraft
 * compiles their GLSL itself, so with Graphics API set to Vulkan they run on the native Vulkan
 * renderer, and on OpenGL they still work. No VulkanMod, no Sodium, no Iris.
 */
public final class EtnaClient implements ClientModInitializer {
	public static final String MOD_ID = "etna";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Identifier FINAL = Identifier.fromNamespaceAndPath(MOD_ID, "etna");

	private static volatile @Nullable Identifier activeEffect;

	private boolean toggleWasDown;

	/** The post effect to add to this frame, or null when Etna is off or no world is loaded. */
	public static @Nullable Identifier activeEffect() {
		return activeEffect;
	}

	@Override
	public void onInitializeClient() {
		EtnaConfig.load();
		EtnaFoliage.register();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		LOGGER.info("Etna loaded (enabled={}, quality={}, profile={})", EtnaConfig.enabled, EtnaConfig.quality, EtnaConfig.profile);
	}

	private void tick(Minecraft mc) {
		handleToggleKey(mc);
		EtnaFoliage.update(mc);
		EtnaMotes.tick(mc);
		activeEffect = EtnaConfig.enabled && mc.level != null ? FINAL : null;
	}

	private void handleToggleKey(Minecraft mc) {
		boolean down = mc.gui.screen() == null && InputConstants.isKeyDown(EtnaConfig.toggleKey);
		if (down && !toggleWasDown) {
			// A benchmark run's settings are temporary: put the player's own back before saving them.
			EtnaBenchmark.stop();
			EtnaConfig.enabled = !EtnaConfig.enabled;
			EtnaConfig.save();
			LOGGER.info("Etna shaders {}", EtnaConfig.enabled ? "on" : "off");
		}
		toggleWasDown = down;
	}
}
