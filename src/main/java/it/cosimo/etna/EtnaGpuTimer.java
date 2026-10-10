package it.cosimo.etna;

import java.util.Locale;
import java.util.OptionalLong;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.jspecify.annotations.Nullable;

/**
 * GPU time per pass, for the performance log and the benchmark: the GPU writes its clock into a query at a
 * few points of each frame, and the differences say how long each part of the frame took on the GPU.
 *
 * Every point sits between two render passes. On Apple GPUs MoltenVK can only read the GPU clock where one
 * Metal encoder ends and the next begins, so a timestamp written inside a pass would read as the end of that
 * pass; between passes it reads as "everything recorded before this is done", which is what an interval
 * needs. The points, in the order a frame passes them, are the {@link Mark}s; {@link #PASSES} names the
 * parts of the frame between them.
 *
 * The GPU writes a frame's timestamps a few frames after the CPU recorded them, so they are read back later
 * and never waited for: four slots of queries, one frame each, are used in turn; a slot is read once all of
 * its queries have come back (normally two frames later), and a frame that finds every slot still in flight
 * is simply not timed. Recording a timestamp resets its query on the CPU at once, which must never happen
 * while the GPU may still write that query, so a slot is only reused after it has come back.
 *
 * Timestamps cost a little: once a frame has one, MoltenVK makes every render pass wait for the previous one
 * to finish. So the timer only runs while the performance log is on or the benchmark runs, only on frames
 * that draw the world, and only on one frame in {@link #EVERY}, so the frame rate around it stays the normal
 * one. When MoltenVK cannot sample the GPU's clock it gives every query of a frame the same time; after a run
 * of such frames the timer says so once in the log and stops, and the log and the benchmark show CPU times
 * only. Everything here runs on the render thread.
 */
public final class EtnaGpuTimer {
	/** Where a frame gets a timestamp, in the order the frame passes them. Each is written once a frame, outside any render pass. */
	public enum Mark {
		/** GameRenderer.render() starts. */
		FRAME_START,
		/** Right before Etna's shadow setup, which records the shadow pass. */
		SHADOW_START,
		/** Right after it. */
		SHADOW_END,
		/** The level is drawn: Etna's world pass (ambient occlusion, anti-aliasing) comes next. */
		LEVEL_END,
		/** After Etna's world pass. */
		WORLD_END,
		/** After the hand and the screen effects drawn with it. */
		HAND_END,
		/** After the post effects: Etna's final pass (bloom, colour grade), then any vanilla effect. */
		FINAL_END,
		/** Just before the frame is submitted: after the HUD and the copy to the window. */
		FRAME_END
	}

	/** The parts of a frame, in the log's order: their index in the array {@link EtnaProfiler#gpuFrame} gets. */
	static final String[] PASSES = {"shadow", "level", "world", "hand", "final", "gui"};

	private static final int MARKS = Mark.values().length;
	private static final int ALL_MARKS = (1 << MARKS) - 1;
	private static final int SLOTS = 4;
	/**
	 * Only one frame in this many gets timestamps: the others run exactly as with the timer off, so the frame rate
	 * the log and the benchmark report is the normal one, and the timed frames still give a steady average (a 5 s
	 * benchmark step at 60 fps books about 37 of them).
	 */
	static final int EVERY = 8;
	/** A slot whose queries have not all come back after this many frames is given up: the GPU is long done with it by then. */
	private static final int GIVE_UP_FRAMES = 60;
	/** Frames in a row whose queries all read the same time (or never came back) before the timer gives up. */
	private static final int BAD_FRAMES_LIMIT = 30;
	/** A frame that took less than this on the GPU did not really get timestamps. */
	private static final long NO_TIME_NS = 1_000L;
	/** A frame can't take this long on the GPU and keep running: the values are wrong. */
	private static final long TOO_LONG_NS = 1_000_000_000L;

	/** One frame's queries: one per Mark, at base + the Mark's ordinal. */
	private static final class Slot {
		final int base;
		/** Bit i: Mark i was written in the frame this slot holds. */
		int written;
		/** A Mark came twice, or at a moment it can't be trusted: the frame is not booked. */
		boolean spoiled;
		/** Written and not all back yet: the queries may still be in use, so the slot must not be recorded again. */
		boolean pending;
		/** EtnaProfiler's number for the frame. */
		long frame;
		int age;

		Slot(int base) {
			this.base = base;
		}
	}

	private static final Slot[] slots = new Slot[SLOTS];
	private static @Nullable GpuQueryPool pool;
	private static @Nullable Slot current;
	/** Between GameRenderer.render() HEAD and RETURN of a timed frame: its Marks are written. */
	private static boolean recording;
	/** render() returned; the frame's last Mark comes at the next submit. */
	private static boolean awaitingEnd;
	/** The slot after the one used last: the oldest in flight, and the first to try for the next frame. */
	private static int nextSlot;
	private static double nsPerTick;
	private static boolean failed;
	private static boolean unavailable;
	private static String unavailableReason = "";
	private static int sameTimeFrames;
	private static int lostFrames;
	private static final long[] ticks = new long[MARKS];
	private static final long[] passNs = new long[PASSES.length];

	static {
		for (int i = 0; i < SLOTS; i++) {
			slots[i] = new Slot(i * MARKS);
		}
	}

	private EtnaGpuTimer() {
	}

	/** Mixin hooks: a point of the frame. One static check unless this frame is being timed. */
	public static void mark(Mark mark) {
		if (!recording) {
			return;
		}
		try {
			write(current, mark);
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/**
	 * Start of GameRenderer.render(): reads the slots in flight without waiting and frees those that came back.
	 * With wanted (the log or the benchmark runs) their frames go to {@link EtnaProfiler#gpuFrame}; otherwise,
	 * or once the driver showed it can't time the GPU, the queries are given back as soon as none is in flight.
	 * One static check while there are none.
	 */
	static void collect(boolean wanted) {
		if (failed || pool == null) {
			return;
		}
		try {
			closeOpenFrame();
			poll(wanted);
			if (!wanted || unavailable) {
				for (Slot slot : slots) {
					if (slot.pending) {
						return;
					}
				}
				closePool();
			}
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** Times this frame on the GPU, after {@link #collect}: only frames that draw the world, and only with a slot free. */
	static void beginFrame(long frame, boolean drawsLevel) {
		if (failed || unavailable || !drawsLevel) {
			return;
		}
		try {
			if (pool == null && !open()) {
				return;
			}
			Slot slot = freeSlot();
			if (slot == null) {
				// Every slot is still in flight (the GPU is more than three frames behind): skip this frame, never wait.
				return;
			}
			slot.written = 0;
			slot.spoiled = false;
			slot.pending = true;
			slot.frame = frame;
			slot.age = 0;
			current = slot;
			recording = true;
			write(slot, Mark.FRAME_START);
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** GameRenderer.render() RETURN: the frame's last Mark waits for the submit, after the copy to the window. */
	static void renderEnd() {
		if (recording) {
			recording = false;
			awaitingEnd = true;
		}
	}

	/** FrontendCommandEncoder.submit() HEAD: the frame's last timestamp. One static check unless a frame is waiting for it. */
	static void beforeSubmit() {
		if (!awaitingEnd) {
			return;
		}
		awaitingEnd = false;
		try {
			write(current, Mark.FRAME_END);
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** Whether GPU times can come: false once the driver showed it has no usable timestamps, or the timer failed. */
	static boolean available() {
		return !failed && !unavailable;
	}

	/** One line on what the GPU figures rest on, for the log's first line and the benchmark table. */
	static String describe() {
		if (failed) {
			return "GPU timing failed (see the error above), CPU times only";
		}
		if (unavailable) {
			return "GPU timing unavailable (" + unavailableReason + "), CPU times only";
		}
		if (nsPerTick <= 0.0) {
			return "GPU timestamps between passes, one frame in " + EVERY;
		}
		return String.format(Locale.ROOT, "GPU timestamps between passes, one frame in %d, %.3f ns per tick", EVERY, nsPerTick);
	}

	private static boolean open() {
		double period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
		if (!(period > 0.0) || Double.isInfinite(period)) {
			giveUp("the driver gives no timestamp period");
			return false;
		}
		nsPerTick = period;
		// A new pool starts with every query reset, so queries a frame did not write simply read as not available.
		pool = RenderSystem.getDevice().createTimestampQueryPool(SLOTS * MARKS);
		for (Slot slot : slots) {
			slot.pending = false;
		}
		nextSlot = 0;
		sameTimeFrames = 0;
		lostFrames = 0;
		return true;
	}

	private static @Nullable Slot freeSlot() {
		for (int i = 0; i < SLOTS; i++) {
			int index = (nextSlot + i) % SLOTS;
			if (!slots[index].pending) {
				nextSlot = (index + 1) % SLOTS;
				return slots[index];
			}
		}
		return null;
	}

	/**
	 * A frame still open when the next one starts (its render() threw, or no submit came): its queries were
	 * written and wait for the GPU like any other, but the frame is not booked.
	 */
	private static void closeOpenFrame() {
		if ((recording || awaitingEnd) && current != null) {
			current.spoiled = true;
		}
		recording = false;
		awaitingEnd = false;
	}

	private static void write(@Nullable Slot slot, Mark mark) {
		GpuQueryPool queries = pool;
		if (slot == null || queries == null) {
			return;
		}
		int bit = 1 << mark.ordinal();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		boolean inPass = encoder instanceof FrontendCommandEncoder frontend && frontend.isInRenderPass();
		if ((slot.written & bit) != 0 || inPass) {
			// Twice in one frame, the second would reset the query before the GPU wrote the first; inside a render
			// pass, MoltenVK would move it to the end of that pass. Either way the frame can't be split right.
			slot.spoiled = true;
			return;
		}
		encoder.writeTimestamp(queries, slot.base + mark.ordinal());
		slot.written |= bit;
	}

	/** Reads every slot in flight, oldest first, without waiting, and frees those whose queries have all come back. */
	private static void poll(boolean book) {
		GpuQueryPool queries = pool;
		if (queries == null) {
			return;
		}
		for (int i = 0; i < SLOTS; i++) {
			Slot slot = slots[(nextSlot + i) % SLOTS];
			if (!slot.pending) {
				continue;
			}
			if (!read(queries, slot)) {
				if (++slot.age > GIVE_UP_FRAMES) {
					slot.pending = false;
					if (book && ++lostFrames >= BAD_FRAMES_LIMIT) {
						giveUp("the driver never returned the timestamps");
					}
				}
				continue;
			}
			slot.pending = false;
			lostFrames = 0;
			if (book && !slot.spoiled && slot.written == ALL_MARKS) {
				book(slot);
			}
		}
	}

	/** Whether every query the slot's frame wrote is back; for a whole frame, its values are then in ticks. */
	private static boolean read(GpuQueryPool queries, Slot slot) {
		if (slot.written == ALL_MARKS) {
			OptionalLong[] values = queries.getValues(slot.base, MARKS);
			for (int i = 0; i < MARKS; i++) {
				if (values[i].isEmpty()) {
					return false;
				}
				ticks[i] = values[i].getAsLong();
			}
			return true;
		}
		// An incomplete frame: only its written queries hold anything (the others may never have been used).
		for (int i = 0; i < MARKS; i++) {
			if ((slot.written & (1 << i)) != 0 && queries.getValue(slot.base + i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static void book(Slot slot) {
		for (int i = 1; i < MARKS; i++) {
			if (ticks[i] < ticks[i - 1]) {
				// Out of order (a clock that wrapped, or values from different clocks): not a frame to split.
				return;
			}
		}
		long totalNs = interval(Mark.FRAME_START, Mark.FRAME_END);
		if (totalNs < NO_TIME_NS) {
			// MoltenVK gives every query of a command buffer the same CPU time when it can't sample the GPU.
			if (++sameTimeFrames >= BAD_FRAMES_LIMIT) {
				giveUp("every timestamp of a frame reads the same time, as MoltenVK does when it can't sample the GPU's clock");
			}
			return;
		}
		sameTimeFrames = 0;
		if (totalNs > TOO_LONG_NS) {
			return;
		}
		passNs[0] = interval(Mark.SHADOW_START, Mark.SHADOW_END);
		// The level is everything from the frame's start up to the world pass, but for the shadow pass inside it.
		passNs[1] = interval(Mark.FRAME_START, Mark.SHADOW_START) + interval(Mark.SHADOW_END, Mark.LEVEL_END);
		passNs[2] = interval(Mark.LEVEL_END, Mark.WORLD_END);
		passNs[3] = interval(Mark.WORLD_END, Mark.HAND_END);
		passNs[4] = interval(Mark.HAND_END, Mark.FINAL_END);
		passNs[5] = interval(Mark.FINAL_END, Mark.FRAME_END);
		EtnaProfiler.gpuFrame(slot.frame, passNs, totalNs);
	}

	private static long interval(Mark from, Mark to) {
		return Math.round((ticks[to.ordinal()] - ticks[from.ordinal()]) * nsPerTick);
	}

	/** The driver can't time the GPU: say so once and stop timing; collect gives the queries back once they are. */
	private static void giveUp(String why) {
		if (unavailable) {
			return;
		}
		unavailable = true;
		unavailableReason = why;
		recording = false;
		EtnaClient.LOGGER.warn("[Etna] GPU timing is unavailable: {}. The performance log and the benchmark show CPU times only.", why);
	}

	private static void fail(RuntimeException e) {
		failed = true;
		recording = false;
		awaitingEnd = false;
		EtnaClient.LOGGER.error("Etna: GPU timing failed, the performance log and the benchmark show CPU times only", e);
		try {
			closePool();
		} catch (RuntimeException ignored) {
			// The pool goes with the device at the latest, and nothing else uses it.
		}
	}

	private static void closePool() {
		GpuQueryPool queries = pool;
		pool = null;
		current = null;
		for (Slot slot : slots) {
			slot.pending = false;
		}
		if (queries != null) {
			// Vulkan destroys it only once the GPU is done with the frames that may still use it.
			queries.close();
		}
	}
}
