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
	public enum Page { GENERAL, MOOD, LIGHTING, SURFACES, SKY, WONDERS, WATER, EFFECTS, COLORS }

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
	/**
	 * The option rows of this page, top to bottom, and how many of them are scrolled past above the column. Only
	 * the rows in view are added to the screen; scrolling builds the page again.
	 */
	private final List<AbstractWidget> rows = new ArrayList<>();
	private int scroll;
	/** The part of a row the wheel or trackpad has scrolled so far, as a trackpad sends small steps. */
	private double scrollRemainder;

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
		// The page keeps its rows when it is built again (scrolled, or the window resized), so the last row
		// stays at the bottom of the column at most.
		this.scroll = Math.clamp(this.scroll, 0, this.maxScroll());
		this.rows.clear();

		// The page tabs close up on short screens (the smallest GUI is 240 high), so the last one stays clear
		// of Reset All below them.
		Page[] pages = Page.values();
		int tabStep = Math.min(ROW_HEIGHT, (this.height - 32 - TOP) / pages.length);
		int tabHeight = Math.min(20, tabStep - 2);
		int y = TOP;
		for (Page target : pages) {
			Button tab = Button.builder(pageName(target), button -> this.open(target)).bounds(8, y, SIDEBAR_WIDTH - 16, tabHeight).build();
			tab.active = target != this.page;
			this.addRenderableWidget(tab);
			y += tabStep;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("etna.options.reset"), button -> {
			EtnaConfig.applyQuality(EtnaConfig.Quality.HIGH);
			EtnaConfig.applyProfile(EtnaConfig.Profile.NATURAL);
			EtnaConfig.visibility.set(EtnaConfig.VISIBILITY_DEFAULT);
			EtnaConfig.sunPathTilt = EtnaConfig.SUN_PATH_TILT_DEFAULT;
			// 0.8.0 MOOD
			EtnaConfig.mood = EtnaConfig.Mood.NATURAL;
			EtnaConfig.moodStrength.set(EtnaConfig.MOOD_STRENGTH_DEFAULT);
			// 0.8.0 MOTES
			EtnaConfig.fireflies = EtnaConfig.Fireflies.NORMAL;
			// 0.8.0 WEATHER
			EtnaConfig.resetWeather();
			EtnaConfig.resetSurfaces();
			// 0.8.0 WATER
			EtnaConfig.shoreFoam.set(EtnaConfig.SHORE_FOAM_DEFAULT);
			EtnaConfig.waterGlitter.set(EtnaConfig.WATER_GLITTER_DEFAULT);
			EtnaConfig.resetSky();
			EtnaConfig.entityLighting = true;
			this.open(this.page);
		}).bounds(8, this.height - 28, SIDEBAR_WIDTH - 16, 20).build());

		this.contentLeft = SIDEBAR_WIDTH + 12;
		this.controlWidth = Math.max(120, Math.min(CONTROL_WIDTH, this.width - this.contentLeft - 10));
		this.panelLeft = this.contentLeft + this.controlWidth + 14;

		switch (this.page) {
			case GENERAL -> this.generalPage();
			case MOOD -> this.moodPage();
			case LIGHTING -> this.lightingPage();
			case SURFACES -> this.surfacesPage();
			case SKY -> this.skyPage();
			case WONDERS -> this.wondersPage();
			case WATER -> this.waterPage();
			case EFFECTS -> this.effectsPage();
			case COLORS -> this.colorsPage();
		}

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds(this.width - 108, this.height - 28, 100, 20).build());
		this.refresh();
	}

	/** How many rows fit above the Done button: 7 on the smallest GUI (240 high). */
	private int visibleRows() {
		return Math.max(1, (this.height - 32 - TOP - 20) / ROW_HEIGHT + 1);
	}

	private int maxScroll() {
		return Math.max(0, this.rows.size() - this.visibleRows());
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x >= SIDEBAR_WIDTH && x < this.panelLeft && this.maxScroll() > 0) {
			this.scrollRemainder += scrollY;
			int steps = (int) this.scrollRemainder;
			this.scrollRemainder -= steps;
			int next = Math.clamp(this.scroll - steps, 0, this.maxScroll());
			if (next != this.scroll) {
				this.scroll = next;
				this.rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
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
		// Not part of the look either: how many pixels the world is drawn at (EtnaScale), in steps of 5%.
		this.add(new Slider("renderScale", EtnaConfig.RENDER_SCALE_MIN, 1.0, EtnaConfig.renderScale,
			v -> Component.literal(Math.round(v * 20.0) * 5 + "%"), v -> EtnaConfig.renderScale = Math.round((float) v * 20.0F) / 20.0F),
			"renderScale");
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

	/** 0.8.0 MOOD: the mood and its strength, neither part of the Look. */
	private void moodPage() {
		Component name = Component.translatable("etna.options.mood");
		Button mood = this.add(Button.builder(Options.genericValueLabel(name, moodName()), button -> {
			EtnaConfig.mood = next(EtnaConfig.Mood.values(), EtnaConfig.mood);
			this.refresh();
		}).size(this.controlWidth, 20).build(), "mood");
		// The description follows the mood on the button: what it does and the resource packs it suits.
		this.refreshers.add(() -> {
			mood.setMessage(Options.genericValueLabel(name, moodName()));
			Component description = Component.translatable("etna.options.mood." + key(EtnaConfig.mood) + ".desc");
			this.descriptions.put(mood, description);
			if (this.narrow()) {
				mood.setTooltip(Tooltip.create(description));
			}
		});
		this.add(new Slider("moodStrength", EtnaConfig.moodStrength.min, EtnaConfig.moodStrength.max, EtnaConfig.moodStrength.value,
			EtnaSettingsScreen::percentOrOff, v -> EtnaConfig.moodStrength.set((float) v)), "moodStrength");
	}

	private static Component moodName() {
		return Component.translatable("etna.options.mood." + key(EtnaConfig.mood));
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
		// 0.9.0: not part of the Look or the Quality.
		this.cycle("entityLighting", () -> onOff(EtnaConfig.entityLighting), () -> EtnaConfig.entityLighting = !EtnaConfig.entityLighting);
	}

	private void surfacesPage() {
		this.look(EtnaConfig.surfaceDetail, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.shine, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.leafGlow, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.torchWarmth, EtnaSettingsScreen::percentOrOff);
		this.look(EtnaConfig.emission, EtnaSettingsScreen::percentOrOff);
		// 0.8.0 SURFACES: not part of the look, like Visibility: moving them keeps the look as it is.
		for (EtnaConfig.Setting setting : List.of(EtnaConfig.materials, EtnaConfig.puddles, EtnaConfig.sparkles, EtnaConfig.glowingFlowers)) {
			this.add(new Slider(setting.key, setting.min, setting.max, setting.value, EtnaSettingsScreen::percentOrOff,
				v -> setting.set((float) v)), setting.key);
		}
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
		// 0.8.0 WEATHER: not part of the look. The lava light from below shows without a world pass too.
		this.add(new Slider("netherAir", EtnaConfig.netherAir.min, EtnaConfig.netherAir.max, EtnaConfig.netherAir.value,
			EtnaSettingsScreen::percentOrOff, v -> EtnaConfig.netherAir.set((float) v)), "netherAir");
		// 0.8.0 SKY: not part of the look, like Visibility.
		this.add(new Slider("biomeSkyColor", EtnaConfig.biomeSkyColor.min, EtnaConfig.biomeSkyColor.max, EtnaConfig.biomeSkyColor.value,
			EtnaSettingsScreen::percentOrOff, v -> EtnaConfig.biomeSkyColor.set((float) v)), "biomeSkyColor");
	}

	/** 0.8.0 SKY: the night sky's wonders, the rainbow and the End's sky; none of them part of the look. */
	private void wondersPage() {
		this.cycle("nightSky", () -> EtnaConfig.nightSky == EtnaConfig.NightSky.OFF
				? CommonComponents.OPTION_OFF
				: Component.translatable("etna.options.nightSky." + key(EtnaConfig.nightSky)),
			() -> EtnaConfig.nightSky = next(EtnaConfig.NightSky.values(), EtnaConfig.nightSky));
		this.cycle("aurora", () -> EtnaConfig.aurora == EtnaConfig.Aurora.OFF
				? CommonComponents.OPTION_OFF
				: Component.translatable("etna.options.aurora." + key(EtnaConfig.aurora)),
			() -> EtnaConfig.aurora = next(EtnaConfig.Aurora.values(), EtnaConfig.aurora));
		this.cycle("shootingStars", () -> EtnaConfig.shootingStars == EtnaConfig.ShootingStars.OFF
				? CommonComponents.OPTION_OFF
				: Component.translatable("etna.options.shootingStars." + key(EtnaConfig.shootingStars)),
			() -> EtnaConfig.shootingStars = next(EtnaConfig.ShootingStars.values(), EtnaConfig.shootingStars));
		this.cycle("roundMoon", () -> onOff(EtnaConfig.roundMoon), () -> EtnaConfig.roundMoon = !EtnaConfig.roundMoon);
		this.cycle("rainbows", () -> EtnaConfig.rainbows == EtnaConfig.Rainbows.OFF
				? CommonComponents.OPTION_OFF
				: Component.translatable("etna.options.rainbows." + key(EtnaConfig.rainbows)),
			() -> EtnaConfig.rainbows = next(EtnaConfig.Rainbows.values(), EtnaConfig.rainbows));
		this.cycle("endSky", () -> onOff(EtnaConfig.endSky), () -> EtnaConfig.endSky = !EtnaConfig.endSky);
	}

	private void waterPage() {
		this.look(EtnaConfig.underwaterVisibility, 8.0, EtnaSettingsScreen::blocks);
		this.look(EtnaConfig.waterClarity, EtnaSettingsScreen::percent);
		this.look(EtnaConfig.caustics, EtnaSettingsScreen::percentOrOff);
		this.needsShadows(this.look(EtnaConfig.underwaterRays, EtnaSettingsScreen::percentOrOff));
		this.look(EtnaConfig.waterReflections, EtnaSettingsScreen::percentOrOff);
		// 0.8.0 WATER: not part of the look, like Visibility. The foam is found from the shadow map.
		this.needsShadows(this.add(new Slider(EtnaConfig.shoreFoam.key, EtnaConfig.shoreFoam.min, EtnaConfig.shoreFoam.max,
			EtnaConfig.shoreFoam.value, EtnaSettingsScreen::percentOrOff, v -> EtnaConfig.shoreFoam.set((float) v)), EtnaConfig.shoreFoam.key));
		this.add(new Slider(EtnaConfig.waterGlitter.key, EtnaConfig.waterGlitter.min, EtnaConfig.waterGlitter.max,
			EtnaConfig.waterGlitter.value, EtnaSettingsScreen::percentOrOff, v -> EtnaConfig.waterGlitter.set((float) v)), EtnaConfig.waterGlitter.key);
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
		// 0.8.0 MOTES. Not part of the look; drawn by the world pass, which runs with anti-aliasing or with shadows.
		this.cycle("fireflies",
			() -> EtnaConfig.fireflies == EtnaConfig.Fireflies.OFF
				? CommonComponents.OPTION_OFF
				: Component.translatable("etna.options.fireflies." + key(EtnaConfig.fireflies)),
			() -> EtnaConfig.fireflies = next(EtnaConfig.Fireflies.values(), EtnaConfig.fireflies));
		// 0.8.0 WEATHER: not part of the look.
		this.cycle("rainLook", () -> Component.translatable("etna.options.rainLook." + key(EtnaConfig.rainLook)),
			() -> EtnaConfig.rainLook = next(EtnaConfig.RainLook.values(), EtnaConfig.rainLook));
		this.add(new Slider("bloomFog", EtnaConfig.bloomFog.min, EtnaConfig.bloomFog.max, EtnaConfig.bloomFog.value,
			EtnaSettingsScreen::percentOrOff, v -> EtnaConfig.bloomFog.set((float) v)), "bloomFog");
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
		if (this.maxScroll() > 0) {
			// A thin scroll bar right of the column, for the pages with more rows than the screen holds.
			int left = this.contentLeft + this.controlWidth + 2;
			int track = this.visibleRows() * ROW_HEIGHT - 4;
			int thumb = Math.max(12, track * this.visibleRows() / this.rows.size());
			int thumbTop = TOP + (track - thumb) * this.scroll / this.maxScroll();
			graphics.fill(left, TOP, left + 2, TOP + track, 0x40FFFFFF);
			graphics.fill(left, thumbTop, left + 2, thumbTop + thumb, 0xC0FFFFFF);
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
		int index = this.rows.size();
		this.rows.add(widget);
		widget.setPosition(this.contentLeft, TOP + (index - this.scroll) * ROW_HEIGHT);
		widget.setWidth(this.controlWidth);
		if (index < this.scroll || index >= this.scroll + this.visibleRows()) {
			// Scrolled out of view: kept for the page's refreshers, but not on the screen.
			return widget;
		}
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
