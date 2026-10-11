package it.cosimo.etna;

/**
 * Auto Resolution (0.9.0): picks the World Resolution (EtnaScale) frame by frame to hold a frame rate, the way
 * games' dynamic resolution does (the technique as Unreal Engine describes it: a GPU time budget, headroom, a
 * quick drop when over budget and a slow climb back). Etna is GPU-bound on most machines, so the GPU's own time per
 * frame is what is steered, not the frame interval, which VSync and frame limiters would hold at their cap.
 *
 * The GPU time comes from EtnaGpuTimer, which times one frame in eight while Auto Resolution is on (as for the
 * performance log): the frame up to the end of Etna's final pass, without the HUD and the copy to the window,
 * where a swapchain wait for VSync could land. The scale moves in a few fixed steps, so the targets the world is drawn into change seldom
 * and every pass still covers its whole target:
 * - over the budget on two timed frames in a row, one step down; far over it on one, two steps down at once;
 * - one step up only when every timed frame of the last two seconds would still have been well inside the budget
 *   at the bigger size, its level and world passes growing with the pixels and the rest (the shadow pass, the
 *   hand, bloom and grade, which run at their own sizes) staying as they are, so a step up doesn't come straight
 *   back down.
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
	/** Whether every timed frame since roomSince would have fitted one step up. */
	private static boolean room;
	private static long roomSince;
	private static long frame;

	private EtnaAutoScale() {
	}

	/** EtnaScale.wanted: this frame's scale, {@code fixed} (the setting) unless Auto Resolution steers it. */
	static float scale(float fixed) {
		if (!active()) {
			step = 0;
			overBudget = 0;
			room = false;
			// Frames timed while it didn't steer aren't counted once it does.
			firstFrame = frame + 1;
			return fixed;
		}
		return stepScale(step, floor(fixed));
	}

	/** The scale step {@code index} draws at: the step, or the floor where the lowest step goes past it. */
	private static float stepScale(int index, float floor) {
		return Math.max(STEPS[index], floor);
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

	/** The first step at or below the floor: drawn at the floor itself (stepScale). */
	private static int lowestStep(float floor) {
		int lowest = 0;
		while (lowest + 1 < STEPS.length && STEPS[lowest] > floor + 1e-4F) {
			lowest++;
		}
		return lowest;
	}

	/**
	 * EtnaGpuTimer: a timed frame came back, {@code frameNumber} as EtnaProfiler numbered it, with its GPU time per
	 * part in nanoseconds (EtnaGpuTimer.PASSES: shadow, level, world, hand, final, gui).
	 */
	static void gpuFrame(long frameNumber, long[] passNs) {
		if (!active() || frameNumber < firstFrame) {
			return;
		}
		long totalNs = passNs[0] + passNs[1] + passNs[2] + passNs[3] + passNs[4];
		long scaledNs = passNs[1] + passNs[2];
		long now = System.nanoTime();
		double budget = 1.0e9 / EtnaConfig.autoResolutionFps;
		float floor = floor(Math.clamp(EtnaConfig.renderScale, EtnaConfig.RENDER_SCALE_MIN, 1.0F));
		int lowest = lowestStep(floor);
		if (step > lowest) {
			// The floor went up in the settings.
			change(lowest, now);
			return;
		}
		if (totalNs > budget) {
			overBudget++;
			room = false;
			if (step < lowest && now - changedAt >= DOWN_AFTER_NS && (overBudget >= 2 || totalNs > budget * PANIC)) {
				change(Math.min(step + (totalNs > budget * PANIC ? 2 : 1), lowest), now);
			}
			return;
		}
		overBudget = 0;
		if (step == 0) {
			return;
		}
		double grow = (double) stepScale(step - 1, floor) / stepScale(step, floor);
		if (totalNs + scaledNs * (grow * grow - 1.0) > budget * HEADROOM) {
			room = false;
			return;
		}
		if (!room) {
			room = true;
			roomSince = now;
		}
		if (now - roomSince >= UP_AFTER_NS && now - changedAt >= UP_AFTER_NS) {
			change(step - 1, now);
		}
	}

	private static void change(int to, long now) {
		step = to;
		overBudget = 0;
		room = false;
		changedAt = now;
		// Timed frames come back at the start of a frame, before its level is sized: this one is the first at the new step.
		firstFrame = frame;
	}
}
