package it.cosimo.etna.gui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.MacosUtil;
import it.cosimo.etna.EtnaBenchmark;
import it.cosimo.etna.EtnaConfig;
import it.cosimo.etna.EtnaProfiler;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Etna's settings, laid out like Sodium's: pages in a sidebar on the left, one column of options, and
 * a panel on the right that explains the option under the mouse. Quality and Look at the top of the
 * first page set everything else at once; every change applies on the next frame, and in game the
 * world stays visible behind the menu.
 */
public final class EtnaSettingsScreen extends Screen {
	public enum Page { GENERAL, LIGHTING, SURFACES, SKY, WATER, EFFECTS, COLORS }

	private static final int SIDEBAR_WIDTH = 120;
	private static final int TOP = 34;
	private static final int ROW_HEIGHT = 24;
	private static final int CONTROL_WIDTH = 230;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM_TEXT = 0xFFC8C8C8;

	private final Screen lastScreen;
	private final Page page;
	private final Map<AbstractWidget, Component> descriptions = new IdentityHashMap<>();
	private final List<Runnable> refreshers = new ArrayList<>();
	private int contentLeft;
	private int controlWidth;
	private int panelLeft;
	private int row;

	public EtnaSettingsScreen(Screen lastScreen) {
		this(lastScreen, Page.GENERAL);
	}

	private EtnaSettingsScreen(Screen lastScreen, Page page) {
		super(Component.translatable("etna.options.title"));
		this.lastScreen = lastScreen;
		this.page = page;
	}

	@Override
	protected void init() {
		this.descriptions.clear();
		this.refreshers.clear();
		this.row = 0;

		int y = TOP;
		for (Page target : Page.values()) {
			Button tab = Button.builder(pageName(target), button -> this.open(target)).bounds(8, y, SIDEBAR_WIDTH - 16, 20).build();
			tab.active = target != this.page;
			this.addRenderableWidget(tab);
			y += ROW_HEIGHT;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("etna.options.reset"), button -> {
			EtnaConfig.applyQuality(EtnaConfig.Quality.HIGH);
			EtnaConfig.applyProfile(EtnaConfig.Profile.NATURAL);
			EtnaConfig.visibility.set(EtnaConfig.VISIBILITY_DEFAULT);
			EtnaConfig.sunPathTilt = EtnaConfig.SUN_PATH_TILT_DEFAULT;
			this.open(this.page);
		}).bounds(8, this.height - 28, SIDEBAR_WIDTH - 16, 20).build());

		this.contentLeft = SIDEBAR_WIDTH + 12;
		this.controlWidth = Math.max(120, Math.min(CONTROL_WIDTH, this.width - this.contentLeft - 10));
		this.panelLeft = this.contentLeft + this.controlWidth + 14;

		switch (this.page) {
			case GENERAL -> this.generalPage();
			case LIGHTING -> this.lightingPage();
			case SURFACES -> this.surfacesPage();
			case SKY -> this.skyPage();
			case WATER -> this.waterPage();
			case EFFECTS -> this.effectsPage();
			case COLORS -> this.colorsPage();
		}

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(this.width - 108, this.height - 28, 100, 20).build());
		this.refresh();
	}

	private void generalPage() {
		this.cycle("enabled", () -> onOff(EtnaConfig.enabled), () -> EtnaConfig.enabled = !EtnaConfig.enabled);
		this.cycle("quality", () -> Component.translatable("etna.options.quality." + key(EtnaConfig.quality)), () -> {
			EtnaConfig.Quality next = next(EtnaConfig.Quality.values(), EtnaConfig.quality);
			EtnaConfig.applyQuality(next == EtnaConfig.Quality.CUSTOM ? EtnaConfig.Quality.LOW : next);
		});
		this.cycle("profile", () -> Component.translatable("etna.options.profile." + key(EtnaConfig.profile)), () -> {
			EtnaConfig.Profile next = next(EtnaConfig.Profile.values(), EtnaConfig.profile);
			EtnaConfig.applyProfile(next == EtnaConfig.Profile.CUSTOM ? EtnaConfig.Profile.NATURAL : next);
		});
		// Not part of the look: moving it keeps the look as it is.
		this.add(new Slider("visibility", EtnaConfig.visibility.min, EtnaConfig.visibility.max, EtnaConfig.visibility.value,
			EtnaSettingsScreen::percentOrOff, v -> EtnaConfig.visibility.set((float) v)), "visibility");
		if (MacosUtil.IS_MACOS) {
			this.cycle("resolution",
				() -> Component.translatable(EtnaConfig.halfResolution ? "etna.options.resolution.half" : "etna.options.resolution.full"),
				() -> EtnaConfig.halfResolution = !EtnaConfig.halfResolution);
		}
		this.cycle("profiling",
			() -> EtnaConfig.profiling == EtnaConfig.Profiling.OFF
				? CommonComponents.OPTION_OFF
				: Component.translatable("etna.options.profiling." + key(EtnaConfig.profiling)),
			() -> EtnaConfig.profiling = next(EtnaConfig.Profiling.values(), EtnaConfig.profiling));
		// Back to the game, with the settings saved as they are, then the run starts.
		Button benchmark = this.add(Button.builder(Component.translatable("etna.options.benchmark"), button -> {
			this.minecraft.gui.setScreen(null);
			EtnaBenchmark.start();
		}).size(this.controlWidth, 20).build(), "benchmark");
		benchmark.active = this.minecraft.level != null && !EtnaProfiler.broken();
		if (EtnaProfiler.broken()) {
			// It would only close the menu: say why it can't run instead.
			benchmark.setTooltip(Tooltip.create(Component.translatable("etna.benchmark.unavailable")));
		}
	}

	private void lightingPage() {
		this.cycle("shadows", () -> onOff(EtnaConfig.shadows), () -> {
			EtnaConfig.shadows = !EtnaConfig.shadows;
			EtnaConfig.quality = EtnaConfig.Quality.CUSTOM;
		});
		this.cycle("shadowResolution", () -> Component.literal(Integer.toString(EtnaConfig.shadowResolution)), () -> {
			EtnaConfig.shadowResolution = nextResolution(EtnaConfig.shadowResolution);
			EtnaConfig.quality = EtnaConfig.Quality.CUSTOM;
		});
		this.add(new Slider("shadowDistance", 16.0, 256.0, EtnaConfig.shadowDistance,
			EtnaSettingsScreen::blocks,
			v -> {
				EtnaConfig.shadowDistance = Math.round(v / 8.0) * 8.0F;
				EtnaConfig.quality = EtnaConfig.Quality.CUSTOM;
			}), "shadowDistance");
		this.look(EtnaConfig.shadowSoftness, v -> Component.literal(String.format(Locale.ROOT, "%.1f", v)));
		this.look(EtnaConfig.shadowDarkness, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.sunlight, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.caveLight, EtnaSettingsScreen::percentOrOff);
	}

	private void surfacesPage() {
		this.look(EtnaConfig.surfaceDetail, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.shine, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.leafGlow, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.torchWarmth, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.emission, EtnaSettingsScreen::percentOrOff);
	}

	private void skyPage() {
		this.add(new Slider("sunPathTilt", 0.0, 45.0, EtnaConfig.sunPathTilt,
			EtnaSettingsScreen::degreesOrOff,
			v -> EtnaConfig.sunPathTilt = Math.round(v)), "sunPathTilt");
		// The glow around the sun is part of Etna's sky, drawn with or without anti-aliasing.
		this.look(EtnaConfig.skyGlow, EtnaSettingsScreen::percent);
		// Drawn by the world pass, which runs with anti-aliasing or with shadows.
		this.needsWorldPass(this.look(EtnaConfig.atmosphere, EtnaSettingsScreen::percentOrOff));
		this.look(EtnaConfig.haze, EtnaSettingsScreen::percentOrOff);
		this.needsWorldPass(this.look(EtnaConfig.mist, EtnaSettingsScreen::percentOrOff));
		// Drawn from vanilla's cloud texture, with or without shadows.
		this.look(EtnaConfig.cloudShadows, EtnaSettingsScreen::percentOrOff);
		// Drawn by the world pass from the shadow map; with shadows on, a world pass always runs.
		this.needsShadows(this.look(EtnaConfig.lightRays, EtnaSettingsScreen::percentOrOff));
	}

	private void waterPage() {
		this.look(EtnaConfig.underwaterVisibility, 8.0, EtnaSettingsScreen::blocks);
		this.look(EtnaConfig.waterClarity, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.caustics, EtnaSettingsScreen::percentOrOff);
		this.needsShadows(this.look(EtnaConfig.underwaterRays, EtnaSettingsScreen::percentOrOff));
		this.look(EtnaConfig.waterReflections, EtnaSettingsScreen::percentOrOff);
	}

	private void effectsPage() {
		this.cycle("antiAliasing", () -> onOff(EtnaConfig.antiAliasing), () -> {
			EtnaConfig.antiAliasing = !EtnaConfig.antiAliasing;
			EtnaConfig.quality = EtnaConfig.Quality.CUSTOM;
		});
		this.needsAntiAliasing(this.look(EtnaConfig.ambientOcclusion, EtnaSettingsScreen::percentOrOff));
		this.look(EtnaConfig.bloom, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.bloomSize, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.sharpen, EtnaSettingsScreen::percentOrOff);
	}

	private void colorsPage() {
		this.look(EtnaConfig.brightness, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.eyeAdaptation, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.contrast, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.saturation, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.vibrance, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.warmth, EtnaSettingsScreen::signedPercent);
		this.look(EtnaConfig.vignette, EtnaSettingsScreen::percentOrOff);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (this.minecraft.level != null) {
			// Only a light tint: the world stays sharp behind the menu, so each change can be judged live.
			graphics.fill(0, 0, this.width, this.height, 0x30000000);
		} else {
			super.extractBackground(graphics, mouseX, mouseY, a);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, SIDEBAR_WIDTH, this.height, 0x70000000);
		graphics.centeredText(this.font, this.title, SIDEBAR_WIDTH / 2, 14, TEXT);
		graphics.text(this.font, pageName(this.page), this.contentLeft, 14, TEXT);
		super.extractRenderState(graphics, mouseX, mouseY, a);

		int panelWidth = this.width - this.panelLeft - 10;
		if (panelWidth >= 90) {
			Component description = Component.translatable("etna.options.page." + key(this.page) + ".desc");
			for (Map.Entry<AbstractWidget, Component> entry : this.descriptions.entrySet()) {
				if (entry.getKey().isHovered()) {
					description = entry.getValue();
				}
			}
			int bottom = Math.min(this.height - 36, TOP + 6 * ROW_HEIGHT);
			graphics.fill(this.panelLeft - 6, TOP - 6, this.width - 6, bottom, 0x70000000);
			graphics.textWithWordWrap(this.font, description, this.panelLeft, TOP, panelWidth - 4, DIM_TEXT);
		}
	}

	@Override
	public void removed() {
		EtnaConfig.save();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	private void open(Page target) {
		this.minecraft.gui.setScreen(new EtnaSettingsScreen(this.lastScreen, target));
	}

	private void refresh() {
		for (Runnable refresher : this.refreshers) {
			refresher.run();
		}
	}

	/** Greys out a slider while anti-aliasing is off, for effects drawn by the world pass. */
	private void needsAntiAliasing(Slider slider) {
		this.needs(slider, () -> EtnaConfig.antiAliasing, "etna.options.needsAntiAliasing");
	}

	/** Greys out a slider while no world pass runs (anti-aliasing and shadows both off), for effects it draws. */
	private void needsWorldPass(Slider slider) {
		this.needs(slider, () -> EtnaConfig.antiAliasing || EtnaConfig.shadows, "etna.options.needsWorldPass");
	}

	/** Greys out a slider while shadows are off, for effects drawn from the shadow map. */
	private void needsShadows(Slider slider) {
		this.needs(slider, () -> EtnaConfig.shadows, "etna.options.needsShadows");
	}

	/** Greys out a slider while what it needs is off, with a tooltip saying what that is. */
	private void needs(Slider slider, BooleanSupplier available, String why) {
		// Narrow screens show descriptions as tooltips (see add), so keep that one while it is usable.
		Tooltip description = this.narrow() ? Tooltip.create(this.descriptions.get(slider)) : null;
		this.refreshers.add(() -> {
			boolean on = available.getAsBoolean();
			slider.active = on;
			slider.setTooltip(on ? description : Tooltip.create(Component.translatable(why)));
		});
	}

	private boolean narrow() {
		return this.width - this.panelLeft - 10 < 90;
	}

	private <T extends AbstractWidget> T add(T widget, String key) {
		widget.setPosition(this.contentLeft, TOP + this.row++ * ROW_HEIGHT);
		widget.setWidth(this.controlWidth);
		Component description = Component.translatable("etna.options." + key + ".desc");
		this.descriptions.put(widget, description);
		if (this.narrow()) {
			// No room for the side panel: explain it in a tooltip instead.
			widget.setTooltip(Tooltip.create(description));
		}
		return this.addRenderableWidget(widget);
	}

	/** A button that steps through values. Other buttons on the page are relabelled, as one can change another. */
	private void cycle(String key, Supplier<Component> value, Runnable advance) {
		Component name = Component.translatable("etna.options." + key);
		Button button = this.add(Button.builder(Options.genericValueLabel(name, value.get()), b -> {
			advance.run();
			this.refresh();
		}).size(this.controlWidth, 20).build(), key);
		this.refreshers.add(() -> button.setMessage(Options.genericValueLabel(name, value.get())));
	}

	/** A slider for a look setting. Touching it turns the look into "Custom". */
	private Slider look(EtnaConfig.Setting setting, DoubleFunction<Component> label) {
		return this.look(setting, 0.0, label);
	}

	/** A slider for a look setting that moves in whole steps of {@code step} (0: smoothly). */
	private Slider look(EtnaConfig.Setting setting, double step, DoubleFunction<Component> label) {
		return this.add(new Slider(setting.key, setting.min, setting.max, setting.value, label, v -> {
			setting.set((float) (step > 0.0 ? Math.round(v / step) * step : v));
			EtnaConfig.profile = EtnaConfig.Profile.CUSTOM;
		}), setting.key);
	}

	private static Component pageName(Page page) {
		return Component.translatable("etna.options.page." + key(page));
	}

	/** A distance in blocks, in steps of 8. */
	private static Component blocks(double v) {
		return Component.translatable("etna.options.blocks", Math.round(v / 8.0) * 8);
	}

	private static Component percent(double v) {
		return Component.literal(Math.round(v * 100.0) + "%");
	}

	private static Component percentOrOff(double v) {
		return v < 0.005 ? CommonComponents.OPTION_OFF : percent(v);
	}

	/** An angle in whole degrees, Off at 0. */
	private static Component degreesOrOff(double v) {
		long d = Math.round(v);
		return d == 0 ? CommonComponents.OPTION_OFF : Component.literal(d + "\u00B0");
	}

	private static Component signedPercent(double v) {
		long p = Math.round(v * 100.0);
		return Component.literal(p > 0 ? "+" + p + "%" : p + "%");
	}

	private static String key(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	private static Component onOff(boolean on) {
		return on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
	}

	private static <T> T next(T[] values, T current) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == current) {
				return values[(i + 1) % values.length];
			}
		}
		return values[0];
	}

	private static int nextResolution(int current) {
		for (int resolution : EtnaConfig.SHADOW_RESOLUTIONS) {
			if (resolution > current) {
				return resolution;
			}
		}
		return EtnaConfig.SHADOW_RESOLUTIONS[0];
	}

	private static final class Slider extends AbstractSliderButton {
		private final Component name;
		private final double min;
		private final double max;
		private final DoubleFunction<Component> label;
		private final DoubleConsumer apply;

		Slider(String key, double min, double max, double current, DoubleFunction<Component> label, DoubleConsumer apply) {
			super(0, 0, Button.DEFAULT_WIDTH, AbstractSliderButton.DEFAULT_HEIGHT, Component.empty(),
				Math.clamp((current - min) / (max - min), 0.0, 1.0));
			this.name = Component.translatable("etna.options." + key);
			this.min = min;
			this.max = max;
			this.label = label;
			this.apply = apply;
			this.updateMessage();
		}

		private double actual() {
			return this.min + this.value * (this.max - this.min);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Options.genericValueLabel(this.name, this.label.apply(this.actual())));
		}

		@Override
		protected void applyValue() {
			this.apply.accept(this.actual());
		}
	}
}
