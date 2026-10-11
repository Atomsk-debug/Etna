package it.cosimo.etna;

/**
 * Auto Resolution (0.9.0): picks the World Resolution (EtnaScale) frame by frame to hold a frame rate, the way
 * games' dynamic resolution does (the technique as Unreal Engine describes it: a GPU time budget, headroom, a
 * quick drop when over budget and a slow climb back). Etna is GPU-bound on most machines, so the GPU's own time per
 * frame is what is steered, not the frame interval, which VSync and frame limiters would hold at their cap.
 *
 * The GPU time comes from EtnaGpuTimer, which times one frame in eight while Auto Resolution is on (as for the
 * performance log). The scale moves in a few fixed steps, so the targets the world is drawn into change seldom
 * and every pass still covers its whole target:
 * - over the budget on two timed frames in a row, one step down; far over it on one, two steps down at once;
 * - one step up only when every timed frame of the last two seconds would still have been well inside the budget
 *   at the bigger size, counting all of the frame as growing with the pixels (it doesn't: the shadow pass, the
 *   hand and the HUD stay as they are), so a step up does not come straight back down.
 * Frames drawn before a step are not counted after it. It never goes below the World Resolution setting (70% when
 * that is at 100%), never runs during the benchmark, and stays off without GPU timestamps (the frame interval
 * alone can't tell room to spare from a VSync cap). Everything here runs on the render thread.
 */
public final class EtnaAutoScale {
	/** The steps, largest first: about 0.85x the pixels from one to the next. */
	private static final float[] STEPS = {1.0F, 0.92F, 0.85F, 0.77F, 0.70F, 0.63F, 0.56F, 0.50F};
	/** The lowest step when World Resolution is at 100%. */
	private static final float DEFAULT_FLOOR = 0.70F;
	/** A timed frame over the budget times this drops two steps at once. */
	private static final double PANIC = 1.3;
	/** A step up must keep every recent timed frame under the budget times this, at the bigger size. */
	private static final double HEADROOM = 0.88;
	/** How long every timed frame must have had room before a step up, and how long after any change. */
	private static final long UP_AFTER_NS = 2_000_000_000L;
	/** How soon after a change another step down may come: a few timed frames at the new size. */
	private static final long DOWN_AFTER_NS = 300_000_000L;

	private static int step;
	private static int overBudget;
	/** The first frame drawn at the current step: timed frames from before it are not counted. */
	private static long firstFrame;
	private static long changedAt;
	/** Since when every timed frame would have fitted one step up. */
	private static long roomSince = -1L;
	private static long frame;

	private EtnaAutoScale() {
	}

	/** EtnaScale.wanted: this frame's scale, {@code fixed} (the setting) unless Auto Resolution steers it. */
	static float scale(float fixed) {
		if (!active()) {
			step = 0;
			overBudget = 0;
			roomSince = -1L;
			return fixed;
		}
		return Math.max(STEPS[step], floor(fixed));
	}

	/** Whether Auto Resolution steers the scale now. */
	private static boolean active() {
		return EtnaConfig.autoResolutionFps > 0 && !EtnaBenchmark.running() && EtnaGpuTimer.available();
	}

	/** Whether the GPU timer should time frames for Auto Resolution (EtnaProfiler, while the log is off). */
	static boolean wantsTiming() {
		return active() && EtnaScale.couldScale();
	}

	/** EtnaProfiler, at the start of every frame, before the timed frames that came back: the frame number they carry. */
	static void frameStarted(long number) {
		frame = number;
	}

	private static float floor(float fixed) {
		return fixed < 1.0F ? fixed : DEFAULT_FLOOR;
	}

	private static int lowestStep(float floor) {
		int lowest = 0;
		while (lowest + 1 < STEPS.length && STEPS[lowest + 1] >= floor - 1e-4F) {
			lowest++;
		}
		return lowest;
	}

	/** EtnaGpuTimer: a timed frame came back, {@code frameNumber} as EtnaProfiler numbered it. */
	static void gpuFrame(long frameNumber, long totalNs) {
		if (!active() || frameNumber < firstFrame) {
			return;
		}
		long now = System.nanoTime();
		double budget = 1.0e9 / EtnaConfig.autoResolutionFps;
		int lowest = lowestStep(floor(Math.clamp(EtnaConfig.renderScale, EtnaConfig.RENDER_SCALE_MIN, 1.0F)));
		if (step > lowest) {
			// The floor went up in the settings.
			change(lowest, now);
			return;
		}
		if (totalNs > budget) {
			overBudget++;
			roomSince = -1L;
			if (step < lowest && now - changedAt >= DOWN_AFTER_NS && (overBudget >= 2 || totalNs > budget * PANIC)) {
				change(Math.min(step + (totalNs > budget * PANIC ? 2 : 1), lowest), now);
			}
			return;
		}
		overBudget = 0;
		if (step == 0) {
			return;
		}
		double grow = (double) STEPS[step - 1] / STEPS[step];
		if (totalNs * grow * grow > budget * HEADROOM) {
			roomSince = -1L;
			return;
		}
		if (roomSince < 0L) {
			roomSince = now;
		}
		if (now - roomSince >= UP_AFTER_NS && now - changedAt >= UP_AFTER_NS) {
			change(step - 1, now);
		}
	}

	private static void change(int to, long now) {
		step = to;
		overBudget = 0;
		roomSince = -1L;
		changedAt = now;
		// Timed frames come back at the start of a frame, before its level is sized: this one is the first at the new step.
		firstFrame = frame;
	}

	/** For the log: the step Auto Resolution is at, or -1 while it doesn't steer. */
	static float current() {
		return active() ? STEPS[step] : -1.0F;
	}
}
