package it.cosimo.etna;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The "Run benchmark" button on the General page: Etna tries its settings one at a time and writes what
 * each one costs to latest.log.
 *
 * Every step starts from the settings as they were when the button was pressed, changes one thing,
 * waits 1.5 s for it to settle (the shadow map is reallocated, the post chains switch, anti-aliasing
 * refills its history) and then measures for 5 s with {@link EtnaProfiler}: fps, the CPU timers and, where
 * the driver can time the GPU, GPU time per pass ({@link EtnaGpuTimer}). "Etna off" and "Etna as set" run
 * twice, at both ends of the run: how far two measurements of the same settings land apart is the noise,
 * and every single change is compared with the mean of the two "as set" steps.
 *
 * A step whose camera moves or turns, or that hits a frame over 50 ms (a pipeline compile, chunks
 * loading, a garbage collection), starts measuring again half a second later, up to three tries; only a
 * few of those in a run, so it stays within about two minutes. The table marks the steps that stayed
 * disturbed. Fifteen steps take about 100 seconds. The run moves on from the start of each frame; a
 * client-tick listener only watches Etna's key.
 *
 * The settings are only changed in memory. The run never saves them, and puts every one of them back
 * when it ends, when the player leaves the world, opens any menu (the only way to reach Etna's
 * settings) or a loading screen appears, when Etna's key is pressed, and on any error. A menu stops the
 * run on the next frame, before it could show or save a value the benchmark had set. The key saves the
 * settings as it switches Etna (EtnaClient, at the end of a client tick), so the run ends at the start of
 * that same tick: the switch and its save then apply to the player's own settings, never to a step's.
 * The game's AFK frame limit is kept away for the run, since the player is asked not to touch anything.
 */
public final class EtnaBenchmark {
	private static final long SETTLE_NS = 1_500_000_000L;
	private static final long MEASURE_NS = 5_000_000_000L;
	/** A disturbed step waits this long before it measures again: its settings are in place already. */
	private static final long RESETTLE_NS = 500_000_000L;
	/** Camera travel over one step, in blocks, above which the step is disturbed. */
	private static final double MOVE_LIMIT = 0.5;
	/** A frame interval over this, and over LONG_FRAME_FACTOR times the typical one, is a hitch. */
	private static final long LONG_FRAME_NS = 50_000_000L;
	private static final double LONG_FRAME_FACTOR = 3.0;
	/** How quickly the typical interval follows the frames: it mostly reflects the last twenty or so. */
	private static final double TYPICAL_WEIGHT = 0.05;
	/** Measurements of one step at most: the first one and two more. */
	private static final int MAX_ATTEMPTS = 3;
	/** Measurements started again in a whole run at most, which keeps it within about two minutes. */
	private static final int RETRY_BUDGET = 4;
	/** Fewer frames than this in a step: its averages are rough. */
	private static final int FEW_FRAMES = 30;

	/** What a step is for, which decides what it is compared with and what the table notes about it. */
	private enum Role {
		/** Etna off: what Etna costs is measured against it. The first and the last step. */
		ETNA_OFF,
		/** The player's settings: every single change is compared with these. The second and the second to last step. */
		AS_SET,
		/** Changes only what the shadows draw. */
		SHADOWS,
		/** Changes only what is drawn while the camera is under water. */
		UNDER_WATER,
		OTHER
	}

	/**
	 * One step: its name in the table, its role, the one change it makes to the settings as set, and whether
	 * that change does anything to the player's settings (if not, the step measures what "as set" does).
	 */
	private record Step(String name, Role role, Runnable change, Predicate<Snapshot> changes) {
	}

	/** shadowCulling=false turns off all of EtnaShadows' caster culling: the floor, the view test and solid back faces. */
	private static final String CULLING_OFF = "shadow culling off";
	/**
	 * Since 0.7.0, with shadows on, anti-aliasing off still runs the plain world pass (light rays, mist, sky glow
	 * and atmosphere; EtnaFrame): the step then measures anti-aliasing and ambient occlusion, not the whole world
	 * pass as in 0.6.0.
	 */
	private static final String AA_OFF = "anti-aliasing off";

	private static final List<Step> STEPS = List.of(
		new Step("Etna off", Role.ETNA_OFF, () -> EtnaConfig.enabled = false, base -> true),
		new Step("Etna as set", Role.AS_SET, () -> {
		}, base -> true),
		// Since 0.7.0 the light rays and the mist are one world pass: with both off it has nothing to march or
		// add, so this step measures all of it. Not a shadows step: the mist runs without shadows too.
		new Step("light rays+mist off", Role.OTHER, () -> {
			EtnaConfig.lightRays.set(0.0F);
			EtnaConfig.mist.set(0.0F);
		}, base -> base.look(EtnaConfig.lightRays) > 0.0F || base.look(EtnaConfig.mist) > 0.0F),
		new Step("hard shadow edges", Role.SHADOWS, () -> EtnaConfig.shadowSoftness.set(0.25F),
			base -> base.look(EtnaConfig.shadowSoftness) != 0.25F),
		new Step("shadow map 2048", Role.SHADOWS, () -> EtnaConfig.shadowResolution = 2048,
			base -> base.shadowResolution != 2048),
		new Step("shadow distance 96", Role.SHADOWS, () -> EtnaConfig.shadowDistance = 96.0F,
			base -> base.shadowDistance != 96.0F),
		new Step(CULLING_OFF, Role.SHADOWS, () -> EtnaConfig.shadowCulling = false,
			base -> base.shadowCulling),
		new Step("shadows off", Role.SHADOWS, () -> EtnaConfig.shadows = false,
			base -> base.shadows),
		new Step(AA_OFF, Role.OTHER, () -> EtnaConfig.antiAliasing = false,
			base -> base.antiAliasing),
		new Step("surface detail+shine off", Role.OTHER, () -> {
			EtnaConfig.surfaceDetail.set(0.0F);
			EtnaConfig.shine.set(0.0F);
		}, base -> base.look(EtnaConfig.surfaceDetail) > 0.0F || base.look(EtnaConfig.shine) > 0.0F),
		new Step("leaf glow off", Role.OTHER, () -> EtnaConfig.leafGlow.set(0.0F),
			base -> base.look(EtnaConfig.leafGlow) > 0.0F),
		new Step("atmosphere+sky glow off", Role.OTHER, () -> {
			EtnaConfig.atmosphere.set(0.0F);
			EtnaConfig.skyGlow.set(0.0F);
		}, base -> base.look(EtnaConfig.atmosphere) > 0.0F || base.look(EtnaConfig.skyGlow) > 0.0F),
		new Step("water rays+caustics off", Role.UNDER_WATER, () -> {
			EtnaConfig.underwaterRays.set(0.0F);
			EtnaConfig.caustics.set(0.0F);
		}, base -> base.look(EtnaConfig.underwaterRays) > 0.0F || base.look(EtnaConfig.caustics) > 0.0F),
		new Step("Etna as set, again", Role.AS_SET, () -> {
		}, base -> true),
		new Step("Etna off, again", Role.ETNA_OFF, () -> EtnaConfig.enabled = false, base -> true)
	);

	/** The settings as they were when the run started; null when no run is going. */
	private static @Nullable Snapshot snapshot;
	private static @Nullable ClientLevel level;
	/** -1 until the first frame of the run applies step 0. */
	private static int step = -1;
	private static boolean settling;
	/** How long the settling under way lasts: a new step's, or the shorter wait before a step measures again. */
	private static long settleNs = SETTLE_NS;
	private static long phaseStart;
	/** What EtnaConfig.enabled must be: anything else means the toggle key was pressed. */
	private static boolean expectedEnabled;
	private static final EtnaProfiler.Stats window = new EtnaProfiler.Stats();
	private static boolean measuring;
	/** Each finished step's figures; null for steps not measured. */
	private static final EtnaProfiler.Result[] results = new EtnaProfiler.Result[STEPS.size()];
	/** How many times each step was measured. */
	private static final int[] attempts = new int[STEPS.size()];
	/** Frames over the hitch limit in the measurement each step kept. */
	private static final int[] longFrames = new int[STEPS.size()];
	/** What disturbed each step's earlier measurements; null where none was. */
	private static final String[] retryReasons = new String[STEPS.size()];
	private static int retriesLeft;
	/** The frame interval the game runs at lately, for a hitch to stand out from; 0 until the first frame. */
	private static double typicalNs;
	/** Frames over the hitch limit in the measurement under way. */
	private static int windowLongFrames;
	private static String setupText = "";
	private static boolean keyWatched;

	private EtnaBenchmark() {
	}

	/** Called by the settings menu's button, after the menu has closed and saved the settings. */
	public static void start() {
		Minecraft mc = Minecraft.getInstance();
		if (snapshot != null || mc.level == null) {
			return;
		}
		if (EtnaProfiler.broken()) {
			// The menu greys the button out then; this is for any other way in, so it never fails silently.
			EtnaClient.LOGGER.warn("[Etna] Benchmark not started: the performance log stopped after an error earlier in this session");
			mc.showDebugChat(Component.translatable("etna.benchmark.unavailable"));
			return;
		}
		watchKey();
		// The JVM's garbage collection counters load on first use, which takes a moment: now, not inside a step.
		EtnaProfiler.gcTotals();
		snapshot = new Snapshot();
		level = mc.level;
		step = -1;
		settling = true;
		settleNs = SETTLE_NS;
		measuring = false;
		expectedEnabled = EtnaConfig.enabled;
		Arrays.fill(results, null);
		Arrays.fill(attempts, 0);
		Arrays.fill(longFrames, 0);
		Arrays.fill(retryReasons, null);
		retriesLeft = RETRY_BUDGET;
		typicalNs = 0.0;
		windowLongFrames = 0;
		setupText = EtnaProfiler.setup(mc);
		long seconds = plannedSeconds();
		EtnaClient.LOGGER.info("[Etna] Benchmark started: {} steps, {} s each after {} s to settle, about {} s in all. A step whose camera"
				+ " moves or that has a frame over {} ms is measured again, {} times at most in the whole run. {}",
			STEPS.size(), MEASURE_NS / 1_000_000_000L, SETTLE_NS / 1.0e9, seconds, LONG_FRAME_NS / 1_000_000L, RETRY_BUDGET, setupText);
		mc.showDebugChat(Component.translatable("etna.benchmark.start", seconds));
	}

	/** Whether a run is going. Its settings are temporary: they must not be saved. */
	public static boolean running() {
		return snapshot != null;
	}

	/**
	 * Stops a run and puts every setting back, now. For code about to save the settings or change one,
	 * so it works on the player's own values (the toggle key, for one).
	 */
	public static void stop() {
		if (snapshot != null) {
			stop(Stop.OTHER, false);
		}
	}

	/**
	 * Registers {@link #beforeTick} once. Fabric runs START_CLIENT_TICK listeners at the start of
	 * Minecraft.tick() and EtnaClient's toggle key at its end (END_CLIENT_TICK). The game polls input
	 * events once per loop, before that loop's ticks and frame, so the key state can't change within a
	 * tick: a key that will switch Etna in a tick is already down when that tick starts.
	 */
	private static void watchKey() {
		if (!keyWatched) {
			keyWatched = true;
			ClientTickEvents.START_CLIENT_TICK.register(EtnaBenchmark::beforeTick);
		}
	}

	/**
	 * Start of every client tick: with Etna's key down, the run ends here, before the key's save at the
	 * end of the tick could write a step's values to etna.properties (the performance log the run turns
	 * on, for one, is read back on every launch). The key is checked even with a menu open: a menu stops
	 * the run anyway, and a menu that closes during the tick must not let a step's values through.
	 */
	private static void beforeTick(Minecraft mc) {
		if (snapshot == null) {
			return;
		}
		try {
			if (!InputConstants.isKeyDown(EtnaConfig.toggleKey)) {
				return;
			}
			Stop reason = interruption(mc);
			// Unless something already switched Etna (reason KEY), put back all of the player's settings,
			// enabled included, so the key switches their own value and saves their own settings.
			stop(reason != null ? reason : Stop.KEY, reason == Stop.KEY);
		} catch (RuntimeException e) {
			EtnaClient.LOGGER.error("Etna: the benchmark failed", e);
			stop(Stop.ERROR, false);
		}
	}

	/** The stats the profiler adds each frame interval (and each frame's GPU times) to while a step is being measured, else null. */
	static EtnaProfiler.@Nullable Stats measuring() {
		return measuring ? window : null;
	}

	/**
	 * Start of every frame while a run is going, after the profiler booked the last interval (interval, in
	 * nanoseconds; -1 when there was none) into the step being measured.
	 */
	static void tick(long now, long interval) {
		if (snapshot == null) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			Stop reason = interruption(mc);
			if (reason != null) {
				// Etna already switched by the time a frame starts: that switch has been saved, keep it.
				stop(reason, reason == Stop.KEY);
				return;
			}
			// Standing still is the point here: keep the game's "AFK" frame limit (30 fps after a minute
			// without input) out of the run.
			mc.getFramerateLimitTracker().onInputReceived();
			if (step < 0) {
				begin(0, now);
				return;
			}
			if (interval > 0L) {
				watchFrame(interval);
			}
			long elapsed = now - phaseStart;
			if (settling) {
				if (elapsed >= settleNs) {
					settling = false;
					phaseStart = now;
					window.reset(now);
					windowLongFrames = 0;
					attempts[step]++;
					measuring = true;
				}
				return;
			}
			String disturbance = disturbance();
			if (disturbance != null && attempts[step] < MAX_ATTEMPTS && retriesLeft > 0) {
				// Better to start this step over now than to finish a measurement that is spoiled already.
				retriesLeft--;
				if (retryReasons[step] == null) {
					retryReasons[step] = disturbance;
				} else if (!retryReasons[step].contains(disturbance)) {
					retryReasons[step] += ", " + disturbance;
				}
				measuring = false;
				settling = true;
				settleNs = RESETTLE_NS;
				phaseStart = now;
				return;
			}
			if (elapsed >= MEASURE_NS) {
				measuring = false;
				results[step] = window.result();
				longFrames[step] = windowLongFrames;
				if (step + 1 < STEPS.size()) {
					begin(step + 1, now);
				} else {
					finish();
				}
			}
		} catch (RuntimeException e) {
			EtnaClient.LOGGER.error("Etna: the benchmark failed", e);
			stop(Stop.ERROR, false);
		}
	}

	/**
	 * Follows the typical frame interval and counts the hitches in the step being measured. A hitch only
	 * nudges the typical interval, while a new step's settings move it within a fraction of the settling time.
	 */
	private static void watchFrame(long interval) {
		if (typicalNs <= 0.0) {
			typicalNs = interval;
			return;
		}
		if (measuring && interval > LONG_FRAME_NS && interval > LONG_FRAME_FACTOR * typicalNs) {
			windowLongFrames++;
		}
		typicalNs += TYPICAL_WEIGHT * (Math.min(interval, 2.0 * typicalNs) - typicalNs);
	}

	/** What spoils the measurement under way, or null while nothing does. */
	private static @Nullable String disturbance() {
		if (window.motion > MOVE_LIMIT || window.turned) {
			return "camera moved";
		}
		if (windowLongFrames > 0) {
			return "a frame over " + LONG_FRAME_NS / 1_000_000L + " ms";
		}
		return null;
	}

	/** The profiler hit an error and stops timing: the run can't go on without it. */
	static void stopAfterError() {
		if (snapshot != null) {
			stop(Stop.ERROR, false);
		}
	}

	private static @Nullable Stop interruption(Minecraft mc) {
		if (mc.level == null || mc.level != level) {
			return Stop.WORLD;
		}
		if (EtnaConfig.enabled != expectedEnabled) {
			return Stop.KEY;
		}
		if (mc.gui.screen() != null || mc.gui.overlay() != null) {
			return Stop.MENU;
		}
		return null;
	}

	private static void begin(int index, long now) {
		Snapshot base = snapshot;
		base.restore();
		// Every step but "Etna off" measures Etna on, even if it was off when the run started.
		EtnaConfig.enabled = true;
		// The shadow pass only counts its casters, and the GPU is only timed, with the log on.
		EtnaConfig.profiling = EtnaConfig.Profiling.SUMMARY;
		STEPS.get(index).change().run();
		expectedEnabled = EtnaConfig.enabled;
		step = index;
		settling = true;
		settleNs = SETTLE_NS;
		measuring = false;
		phaseStart = now;
	}

	private static void finish() {
		restore(false);
		try {
			EtnaClient.LOGGER.info(table("[Etna] Benchmark finished, every setting is back as it was."));
		} catch (RuntimeException e) {
			EtnaClient.LOGGER.error("Etna: could not write the benchmark table", e);
		} finally {
			clear();
		}
		Minecraft.getInstance().showDebugChat(Component.translatable("etna.benchmark.done"));
	}

	/**
	 * Ends the run and puts the settings back. keepEnabled: Etna was already switched with its key, and
	 * that switch was saved, so EtnaConfig.enabled stays as it is now.
	 */
	private static void stop(Stop reason, boolean keepEnabled) {
		if (snapshot == null) {
			return;
		}
		// Settings first: nothing below may keep them from coming back.
		restore(keepEnabled);
		try {
			String head = "[Etna] Benchmark stopped (" + reason.text + "), every setting is back as it was.";
			if (keepEnabled) {
				head += " Etna stays " + (EtnaConfig.enabled ? "on" : "off") + ", as the key set it.";
			} else if (reason == Stop.KEY) {
				head += " The key now switches Etna from the player's own setting.";
			}
			if (hasResults()) {
				EtnaClient.LOGGER.warn(table(head + " The steps it finished:"));
			} else {
				EtnaClient.LOGGER.warn(head);
			}
		} catch (RuntimeException e) {
			EtnaClient.LOGGER.error("Etna: could not write the benchmark table", e);
		} finally {
			clear();
		}
		if (reason != Stop.OTHER) {
			Minecraft.getInstance().showDebugChat(Component.translatableWithFallback("etna.benchmark.stopped",
				"Etna benchmark stopped: %s. Every setting is back as it was.",
				Component.translatableWithFallback("etna.benchmark.stopped." + reason.key, reason.text)));
		}
	}

	private static boolean hasResults() {
		for (EtnaProfiler.Result result : results) {
			if (result != null) {
				return true;
			}
		}
		return false;
	}

	/** Puts the settings back; with keepEnabled, all but EtnaConfig.enabled. */
	private static void restore(boolean keepEnabled) {
		Snapshot base = snapshot;
		if (base == null) {
			return;
		}
		boolean enabled = EtnaConfig.enabled;
		base.restore();
		if (keepEnabled) {
			EtnaConfig.enabled = enabled;
		}
	}

	private static void clear() {
		snapshot = null;
		level = null;
		measuring = false;
		settling = false;
		step = -1;
	}

	/** The run's length when no step is measured again, rounded to 5 s, for the messages. */
	private static long plannedSeconds() {
		double seconds = STEPS.size() * (SETTLE_NS + MEASURE_NS) / 1.0e9;
		return Math.round(seconds / 5.0) * 5L;
	}

	/**
	 * The mean of the steps with one role ("Etna as set" or "Etna off"), and how far apart their measurements
	 * landed (-1 with fewer than two). The GPU figures count only the steps whose GPU times came back.
	 */
	private record Reference(int count, double intervalMs, double spreadMs, int gpuCount, double gpuMs, double gpuSpreadMs,
			double[] gpuPassMs) {
	}

	private static Reference reference(Role role) {
		int count = 0;
		double sum = 0.0;
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		int gpuCount = 0;
		double gpuSum = 0.0;
		double gpuMin = Double.MAX_VALUE;
		double gpuMax = -Double.MAX_VALUE;
		double[] passSum = new double[EtnaGpuTimer.PASSES.length];
		for (int i = 0; i < STEPS.size(); i++) {
			EtnaProfiler.Result r = results[i];
			if (r == null || STEPS.get(i).role() != role) {
				continue;
			}
			count++;
			sum += r.intervalMs();
			min = Math.min(min, r.intervalMs());
			max = Math.max(max, r.intervalMs());
			if (r.gpuFrames() > 0) {
				gpuCount++;
				gpuSum += r.gpuFrameMs();
				gpuMin = Math.min(gpuMin, r.gpuFrameMs());
				gpuMax = Math.max(gpuMax, r.gpuFrameMs());
				for (int p = 0; p < passSum.length; p++) {
					passSum[p] += r.gpuPassMs()[p];
				}
			}
		}
		for (int p = 0; p < passSum.length; p++) {
			passSum[p] = gpuCount > 0 ? passSum[p] / gpuCount : 0.0;
		}
		return new Reference(count, count > 0 ? sum / count : 0.0, count > 1 ? max - min : -1.0,
			gpuCount, gpuCount > 0 ? gpuSum / gpuCount : 0.0, gpuCount > 1 ? gpuMax - gpuMin : -1.0, passSum);
	}

	private static String table(String head) {
		StringBuilder out = new StringBuilder(8192);
		Snapshot base = snapshot;
		Reference set = reference(Role.AS_SET);
		Reference off = reference(Role.ETNA_OFF);
		boolean gpu = false;
		for (EtnaProfiler.Result r : results) {
			gpu |= r != null && r.gpuFrames() > 0;
		}
		out.append(head).append('\n');
		out.append("  ").append(setupText).append("; ").append(EtnaGpuTimer.describe()).append('\n');
		out.append(String.format(Locale.ROOT, "  %d s per step after %.1f s to settle; a step whose camera moved or that had a frame over %d ms"
				+ " was measured again (%d times at most). Times are milliseconds per frame, lower is better.\n",
			MEASURE_NS / 1_000_000_000L, SETTLE_NS / 1.0e9, LONG_FRAME_NS / 1_000_000L, MAX_ATTEMPTS));

		StringBuilder header = new StringBuilder(String.format(Locale.ROOT, "  %-2s %-25s %5s %6s %6s %7s",
			"#", "step", "fps", "frame", "p95", "vs set"));
		if (gpu) {
			header.append(String.format(Locale.ROOT, " | %5s %7s", "GPU", "vs set"));
			for (String pass : EtnaGpuTimer.PASSES) {
				header.append(' ').append(pad(pass, passWidth(pass)));
			}
		}
		out.append(header).append(" | note\n");
		for (int i = 0; i < STEPS.size(); i++) {
			EtnaProfiler.Result r = results[i];
			if (r == null) {
				continue;
			}
			Step s = STEPS.get(i);
			String vs = s.role() == Role.AS_SET ? "ref" : set.count() == 0 ? "-" : signed(r.intervalMs() - set.intervalMs());
			StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-2d %-25s %5.0f %6.1f %6.1f %7s",
				i, s.name(), r.fps(), r.intervalMs(), r.p95Ms(), vs));
			if (gpu) {
				row.append(gpuColumns(r, s, set));
			}
			String note = note(i, r, base);
			if (!note.isEmpty()) {
				row.append(" | ").append(note);
			}
			out.append(row).append('\n');
		}

		summary(out, set, off, gpu);

		out.append(String.format(Locale.ROOT, "  %-2s %-25s %6s %6s %7s | %6s %6s %6s | %6s %6s %6s %3s | %s",
			"#", "step", "render", "setup", "extract", "submit", "encode", "wait", "draws", "passes", "copies", "GC", "casters")).append('\n');
		for (int i = 0; i < STEPS.size(); i++) {
			EtnaProfiler.Result r = results[i];
			if (r == null) {
				continue;
			}
			out.append(String.format(Locale.ROOT, "  %-2d %-25s %6.1f %6.1f %7.1f | %6.1f %6s %6s | %6s %6s %6s %3s | %s",
				i, STEPS.get(i).name(), r.renderMs(), r.setupMs(), r.extractMs(), r.submitMs(),
				r.submitSplit() ? oneDecimal(r.encodeMs()) : "-", r.submitSplit() ? oneDecimal(r.waitMs()) : "-",
				r.counted() ? whole(r.draws()) : "-", r.counted() ? whole(r.passes()) : "-", r.counted() ? whole(r.copies()) : "-",
				r.collections() < 0L ? "-" : Long.toString(r.collections()), r.casters()).stripTrailing()).append('\n');
		}

		out.append("  frame = time between frames (mean; p95: 95% of frames were faster); vs set = change against the mean of the")
			.append(" \"Etna as set\" steps (negative: the step's change saves this much).");
		if (gpu) {
			out.append(" GPU = the frame's time on the GPU, split into shadow (Etna's shadow map), level (the world), world")
				.append(" (Etna's ambient occlusion and anti-aliasing), hand, final (Etna's bloom and colour grade, then vanilla's")
				.append(" effects) and gui (the HUD and the copy to the window).");
		}
		out.append('\n');
		out.append("  render = CPU time recording the frame, of which setup is Etna's shadow setup; extract = CPU time copying the")
			.append(" world for the frame; submit = handing the frame over: encode is MoltenVK turning it into Metal commands, wait")
			.append(" is waiting for the GPU (large: the GPU sets the pace); draws, passes and copies per frame, as Metal runs them;")
			.append(" GC = garbage collection pauses in the step; casters = shadow casters kept/tested.\n");
		out.append("  With a frame-rate limiter or vsync on, fps and frame only show the limit: read GPU, render and submit instead.");
		return out.toString();
	}

	/** The GPU part of a row in the first table: the frame, its change against "as set", then each pass. */
	private static String gpuColumns(EtnaProfiler.Result r, Step s, Reference set) {
		StringBuilder columns = new StringBuilder(64);
		if (r.gpuFrames() == 0) {
			columns.append(String.format(Locale.ROOT, " | %5s %7s", "-", "-"));
			for (String pass : EtnaGpuTimer.PASSES) {
				columns.append(' ').append(pad("-", passWidth(pass)));
			}
			return columns.toString();
		}
		String vs = s.role() == Role.AS_SET ? "ref" : set.gpuCount() == 0 ? "-" : signed(r.gpuFrameMs() - set.gpuMs());
		columns.append(String.format(Locale.ROOT, " | %5.1f %7s", r.gpuFrameMs(), vs));
		for (int i = 0; i < EtnaGpuTimer.PASSES.length; i++) {
			columns.append(' ').append(pad(oneDecimal(r.gpuPassMs()[i]), passWidth(EtnaGpuTimer.PASSES[i])));
		}
		return columns.toString();
	}

	private static int passWidth(String pass) {
		return Math.max(5, pass.length());
	}

	/** Right-aligned in a column this wide. */
	private static String pad(String text, int width) {
		return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
	}

	private static String oneDecimal(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private static String whole(double value) {
		return Long.toString(Math.round(value));
	}

	private static void summary(StringBuilder out, Reference set, Reference off, boolean gpu) {
		if (set.count() > 0 && off.count() > 0 && set.intervalMs() > 0.0 && off.intervalMs() > 0.0) {
			out.append(String.format(Locale.ROOT, "  Etna as set costs %.1f ms per frame over Etna off (%.0f fps against %.0f)",
				set.intervalMs() - off.intervalMs(), 1000.0 / set.intervalMs(), 1000.0 / off.intervalMs()));
			if (gpu && set.gpuCount() > 0 && off.gpuCount() > 0) {
				StringJoiner parts = new StringJoiner(", ");
				for (int i = 0; i < EtnaGpuTimer.PASSES.length; i++) {
					parts.add(EtnaGpuTimer.PASSES[i] + " " + signed(set.gpuPassMs()[i] - off.gpuPassMs()[i]));
				}
				out.append(String.format(Locale.ROOT, "; on the GPU %.1f ms (%s)", set.gpuMs() - off.gpuMs(), parts));
			}
			out.append(".\n");
		}
		StringJoiner noise = new StringJoiner(", ");
		if (set.spreadMs() >= 0.0) {
			noise.add("the two \"Etna as set\" steps differ by " + spread(set, gpu));
		}
		if (off.spreadMs() >= 0.0) {
			noise.add("the two \"Etna off\" steps by " + spread(off, gpu));
		}
		if (noise.length() > 0) {
			out.append("  Noise: ").append(noise).append("; treat smaller differences as noise.\n");
		}
		// A change smaller than the noise is not a saving, nor is one under 0.1 ms.
		double noiseMs = Math.max(0.1, Math.max(set.spreadMs(), off.spreadMs()));
		List<Integer> savers = new ArrayList<>();
		if (set.count() > 0) {
			for (int i = 0; i < STEPS.size(); i++) {
				Role role = STEPS.get(i).role();
				EtnaProfiler.Result r = results[i];
				if (role != Role.AS_SET && role != Role.ETNA_OFF && r != null && r.intervalMs() - set.intervalMs() < -noiseMs) {
					savers.add(i);
				}
			}
		}
		if (!savers.isEmpty()) {
			savers.sort(Comparator.comparingDouble(i -> results[i].intervalMs()));
			StringJoiner list = new StringJoiner(", ");
			for (int i = 0; i < Math.min(4, savers.size()); i++) {
				int index = savers.get(i);
				EtnaProfiler.Result r = results[index];
				String saving = String.format(Locale.ROOT, "%s %.1f ms", STEPS.get(index).name(), r.intervalMs() - set.intervalMs());
				if (gpu && r.gpuFrames() > 0 && set.gpuCount() > 0) {
					saving += String.format(Locale.ROOT, " (GPU %.1f)", r.gpuFrameMs() - set.gpuMs());
				}
				list.add(saving);
			}
			out.append("  Biggest savings: ").append(list).append(".\n");
		}
		StringJoiner again = new StringJoiner(", ");
		StringJoiner still = new StringJoiner(", ");
		for (int i = 0; i < STEPS.size(); i++) {
			if (attempts[i] > 1 && retryReasons[i] != null) {
				again.add(i + " (" + retryReasons[i] + ")");
			}
			EtnaProfiler.Result r = results[i];
			if (r != null && disturbed(i, r)) {
				still.add(Integer.toString(i));
			}
		}
		StringJoiner retried = new StringJoiner(" ", "  ", "\n");
		retried.setEmptyValue("");
		if (again.length() > 0) {
			retried.add("Measured again: step " + again + ".");
		}
		if (still.length() > 0) {
			retried.add("Still disturbed when the tries ran out: step " + still + "; read those rows with care.");
		}
		out.append(retried);
	}

	/** "0.12 ms (GPU 0.05)": how far apart two measurements of the same settings landed. */
	private static String spread(Reference reference, boolean gpu) {
		String text = String.format(Locale.ROOT, "%.2f ms", reference.spreadMs());
		if (gpu && reference.gpuSpreadMs() >= 0.0) {
			text += String.format(Locale.ROOT, " (GPU %.2f)", reference.gpuSpreadMs());
		}
		return text;
	}

	/** Whether the measurement a step kept was still disturbed: it ran out of tries. */
	private static boolean disturbed(int index, EtnaProfiler.Result r) {
		return r.motion() > MOVE_LIMIT || r.turned() || longFrames[index] > 0;
	}

	/** A difference in milliseconds with its sign, and no "-0.0" for one that rounds to nothing. */
	private static String signed(double ms) {
		double rounded = Math.round(ms * 10.0) / 10.0;
		return rounded == 0.0 ? "0.0" : String.format(Locale.ROOT, "%+.1f", rounded);
	}

	private static String note(int index, EtnaProfiler.Result r, @Nullable Snapshot base) {
		StringJoiner note = new StringJoiner(", ");
		if (r.motion() > MOVE_LIMIT || r.turned()) {
			note.add("camera moved");
		}
		if (longFrames[index] > 0) {
			note.add(longFrames[index] + (longFrames[index] == 1 ? " frame" : " frames") + " over " + LONG_FRAME_NS / 1_000_000L + " ms");
		}
		if (attempts[index] > 1) {
			note.add("measured " + attempts[index] + " times");
		}
		if (r.frames() < FEW_FRAMES) {
			note.add("few frames");
		}
		if (r.lowestLimit() < EtnaProfiler.UNLIMITED_FPS) {
			note.add("game limited fps to " + r.lowestLimit());
		}
		Step s = STEPS.get(index);
		if (base != null && !s.changes().test(base)) {
			note.add("same settings as Etna as set");
		} else if (s.role() == Role.SHADOWS && base != null && !base.shadows) {
			note.add("shadows were off");
		}
		if (s.role() == Role.UNDER_WATER) {
			note.add("only differs under water");
		}
		if (s.name().equals(CULLING_OFF)) {
			note.add("floor, view test and back faces all off");
		}
		if (s.name().equals(AA_OFF) && base != null && base.shadows && base.antiAliasing) {
			note.add("world pass kept for rays and mist");
		}
		if (r.gpuFrames() > 0 && r.gpuFrames() * 4 * EtnaGpuTimer.EVERY < r.levelFrames() * 3) {
			note.add("GPU timed " + r.gpuFrames() + " of " + r.levelFrames() + " frames");
		}
		return note.toString();
	}

	/** Why a run ended early: the key of its chat text and the English for the log. */
	private enum Stop {
		WORLD("world", "you left the world"),
		MENU("menu", "a menu or loading screen opened"),
		KEY("key", "Etna was switched with its key"),
		ERROR("error", "an error, see latest.log"),
		OTHER("other", "a setting was about to change");

		final String key;
		final String text;

		Stop(String key, String text) {
			this.key = key;
			this.text = text;
		}
	}

	/** Every setting the run can touch, as it was when it started. */
	private static final class Snapshot {
		final boolean enabled = EtnaConfig.enabled;
		final EtnaConfig.Quality quality = EtnaConfig.quality;
		final EtnaConfig.Profile profile = EtnaConfig.profile;
		final EtnaConfig.Profiling profiling = EtnaConfig.profiling;
		final boolean shadows = EtnaConfig.shadows;
		final int shadowResolution = EtnaConfig.shadowResolution;
		final float shadowDistance = EtnaConfig.shadowDistance;
		final boolean antiAliasing = EtnaConfig.antiAliasing;
		final boolean shadowCulling = EtnaConfig.shadowCulling;
		final float[] look = new float[EtnaConfig.LOOK.size()];

		Snapshot() {
			for (int i = 0; i < this.look.length; i++) {
				this.look[i] = EtnaConfig.LOOK.get(i).value;
			}
		}

		float look(EtnaConfig.Setting setting) {
			return this.look[EtnaConfig.LOOK.indexOf(setting)];
		}

		void restore() {
			EtnaConfig.enabled = this.enabled;
			EtnaConfig.quality = this.quality;
			EtnaConfig.profile = this.profile;
			EtnaConfig.profiling = this.profiling;
			EtnaConfig.shadows = this.shadows;
			EtnaConfig.shadowResolution = this.shadowResolution;
			EtnaConfig.shadowDistance = this.shadowDistance;
			EtnaConfig.antiAliasing = this.antiAliasing;
			EtnaConfig.shadowCulling = this.shadowCulling;
			for (int i = 0; i < this.look.length; i++) {
				// Exactly the old value: Setting.set would clamp, and these came from the same ranges anyway.
				EtnaConfig.LOOK.get(i).value = this.look[i];
			}
		}
	}
}
