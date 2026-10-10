package it.cosimo.etna.mixin;

import it.cosimo.etna.gui.EtnaSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an "Etna Shaders..." button to the top-left corner of the Options screen. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
	protected OptionsScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void etna$addSettingsButton(CallbackInfo ci) {
		Screen options = this;
		this.addRenderableWidget(Button.builder(Component.translatable("etna.options.open"),
				button -> this.minecraft.gui.setScreen(new EtnaSettingsScreen(options)))
			.bounds(6, 6, 100, 20)
			.build());
	}
}
