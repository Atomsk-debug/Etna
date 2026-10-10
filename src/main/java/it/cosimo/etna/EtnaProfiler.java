package it.cosimo.etna;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * The performance log ("Performance log: Summary" on the General page) and the clock the benchmark reads.
 *
 * Measured on the CPU, around points every frame passes:
 * <ul>
 * <li>frame interval: from one GameRenderer.render() start to the next. It holds the whole frame,
 * including the time a frame-rate limiter sleeps, so it is what the fps figure comes from;</li>
 * <li>ticks: Minecraft.tick(), the game's update 20 times a second, run between two frames when one is due.
 * Frames that ran one are counted, and the 95th percentile is also given without them;</li>
 * <li>CPU render: GameRenderer.render() start to return, recording the frame's commands. Etna's shadow
 * setup (EtnaShadows.prepareFrame: light, casters, shadow pass) is part of it and is shown on its own,
 * split into its parts once EtnaShadows reports them ({@link #shadowScan} and the others);</li>
 * <li>CPU extract: GameRenderer.extract(), which copies the world into the frame's render state;</li>
 * <li>acquire+present: getting the next window image before a frame and handing the finished one to the
 * screen after it. Long when no image is free: vsync, or a GPU that is behind;</li>
 * <li>submit: FrontendCommandEncoder.submit(), once a frame after render. On Vulkan it is split in two:
 * encode, handing the frame to the driver (MoltenVK turns it into Metal commands there, on this thread),
 * and wait, until the frame from two submits ago is done on the GPU. A long wait means the GPU sets the
 * pace; a long encode, the driver's CPU work;</li>
 * <li>draws, passes, copies and clears the frame asked the Vulkan backend for. Each pass, copy and clear is
 * a Metal encoder, and an indirect draw counts each of the draws it holds, as Metal runs them;</li>
 * <li>GC: the garbage collection pauses the JVM reports over the stretch, and the time they took.</li>
 * </ul>
 * GPU time per pass comes from {@link EtnaGpuTimer}. Each interval is booked when the next frame starts,
 * so a frame's submit and present (after render) and the next frame's ticks, acquire and extract (before
 * render) land in the interval they took time from. With the log off and no benchmark running, every
 * hook is one static check.
 */
public final class EtnaProfiler {
	private static final long LOG_PERIOD_NS = 5_000_000_000L;
	/** Max element change of the screen-from-camera matrix that still counts as the camera standing still. */
	private static final float TURN_LIMIT = 0.02F;
	/** The game draws as fast as it can at this frame limit ("Unlimited" in Video Settings). */
	static final int UNLIMITED_FPS = 260;
	/** The parts of Etna's shadow setup, in the order EtnaShadows runs them: the index each entry point books into. */
	private static final String[] SHADOW_PARTS = {"scan", "prepare", "record", "ubo"};
	/** Below this many frames without a tick, their 95th percentile says little. */
	private static final int FEW_CALM_FRAMES = 20;

	// The interval being timed: it began at frameStart, the start of the last GameRenderer.render().
	private static boolean timing;
	/** Counts the frames timed; {@link EtnaGpuTimer} hands each frame's GPU times back under this number. */
	private static long frameNumber;
	private static long frameStart;
	private static boolean renderDone;
	private static long renderNs;
	private static boolean extracting;
	private static long extractStart;
	private static long extractNs;
	private static boolean submitting;
	private static long submitStart;
	private static long submitNs;
	private static boolean encoding;
	private static long encodeStart;
	private static long encodeNs;
	private static boolean waiting;
	private static long waitStart;
	private static long waitNs;
	private static boolean ticking;
	private static long tickStart;
	private static long tickNs;
	private static int ticks;
	private static boolean atSurface;
	private static long surfaceStart;
	private static long surfaceNs;
	private static int draws;
	private static int indirectDraws;
	private static int passes;
	private static int copies;
	private static int clears;
	private static final long[] shadowPartNs = new long[SHADOW_PARTS.length];
	private static boolean levelDrawn;
	private static long setupNs;
	private static boolean castersRead;
	private static int candidates;
	private static int kept;
	private static long solidIndices;
	private static long cutoutIndices;
	/** Casters the frustum test dropped this frame, or -1 while nothing counts them. */
	private static int frustumDropped;
	private static float motion;
	private static boolean menuOpen;
	private static int frameLimit;
	private static final Matrix4f frameView = new Matrix4f();

	private static final Stats log = new Stats();
	private static boolean logStarted;
	private static boolean headerDue = true;
	private static boolean broken;
	private static @Nullable List<GarbageCollectorMXBean> collectors;

	private EtnaProfiler() {
	}

	/** GameRenderer.render() HEAD: books the interval that just ended, then runs the benchmark, the log and the GPU timer. */
	public static void frameBegin() {
		if (broken) {
			EtnaGpuTimer.collect(false);
			return;
		}
		if (EtnaConfig.profiling == EtnaConfig.Profiling.OFF && !EtnaBenchmark.running()) {
			if (timing) {
				timing = false;
				logStarted = false;
				headerDue = true;
			}
			// Gives the GPU timer's queries back once the frames still using them are done.
			EtnaGpuTimer.collect(false);
			return;
		}
		try {
			long now = System.nanoTime();
			Minecraft mc = Minecraft.getInstance();
			long interval = -1L;
			// A frame whose render() threw never reached frameEnd: its interval is dropped.
			if (timing && renderDone) {
				interval = now - frameStart;
				log.add(interval, mc);
				Stats bench = EtnaBenchmark.measuring();
				if (bench != null) {
					bench.add(interval, mc);
				}
			}
			timing = true;
			frameNumber++;
			startInterval(now);
			// GPU times of earlier frames that have come back, booked before the log or a step closes its window.
			EtnaGpuTimer.collect(true);
			EtnaBenchmark.tick(now, interval);
			logIfDue(now, mc);
			if ((EtnaConfig.profiling != EtnaConfig.Profiling.OFF || EtnaBenchmark.running())
				&& frameNumber % EtnaGpuTimer.EVERY == 0) {
				EtnaGpuTimer.beginFrame(frameNumber, mc.gameRenderer.gameRenderState().shouldRenderLevel);
			}
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** GameRenderer.render() RETURN: the frame is recorded; read what Etna measured during it. */
	public static void frameEnd() {
		EtnaGpuTimer.renderEnd();
		if (!timing || broken) {
			return;
		}
		try {
			renderNs = System.nanoTime() - frameStart;
			renderDone = true;
			Minecraft mc = Minecraft.getInstance();
			menuOpen = mc.gui.screen() != null;
			frameLimit = mc.gameRenderer.gameRenderState().framerateLimit;
			if (levelDrawn) {
				setupNs = EtnaShadows.lastSetupNs;
				// The counters are only filled while Etna draws shadows: otherwise they hold an old frame's numbers.
				castersRead = EtnaConfig.shadowsActive() && EtnaShadows.casterCandidates > 0;
				if (castersRead) {
					candidates = EtnaShadows.casterCandidates;
					kept = EtnaShadows.castersKept;
					solidIndices = EtnaShadows.solidIndices;
					cutoutIndices = EtnaShadows.cutoutIndices;
					frustumDropped = frustumDropped();
				}
				float mx = EtnaFrame.motionX();
				float my = EtnaFrame.motionY();
				float mz = EtnaFrame.motionZ();
				motion = (float) Math.sqrt(mx * mx + my * my + mz * mz);
				EtnaFrame.viewProjection(frameView);
			}
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/**
	 * Shadow casters EtnaShadows' view test dropped this frame (0 with shadowCulling off, or on a frame whose camera
	 * matrix was stale). Read only on frames that drew shadows, like the counters above; the log line and the
	 * benchmark table show it next to the casters kept and tested.
	 */
	private static int frustumDropped() {
		return EtnaShadows.castersOutsideView;
	}

	/** GameRenderer.renderLevel() HEAD: this frame draws the world. */
	public static void levelBegin() {
		if (timing) {
			levelDrawn = true;
		}
	}

	/** GameRenderer.extract() HEAD, just before the frame's render(). */
	public static void extractBegin() {
		if (timing) {
			extracting = true;
			extractStart = System.nanoTime();
		}
	}

	public static void extractEnd() {
		if (extracting) {
			extracting = false;
			extractNs += System.nanoTime() - extractStart;
		}
	}

	/** FrontendCommandEncoder.submit() HEAD: the game submits once a frame, after render() and the copy to the window. */
	public static void submitBegin() {
		// The frame's last GPU timestamp goes in before the frame is handed over.
		EtnaGpuTimer.beforeSubmit();
		if (timing) {
			submitting = true;
			submitStart = System.nanoTime();
		}
	}

	public static void submitEnd() {
		if (submitting) {
			submitting = false;
			submitNs += System.nanoTime() - submitStart;
		}
	}

	/** VulkanCommandEncoder.submit() HEAD: handing the frame to the driver starts. */
	public static void encodeBegin() {
		if (timing) {
			encoding = true;
			encodeStart = System.nanoTime();
		}
	}

	/** Right before the Vulkan backend waits for the frame from two submits ago: encoding is over, waiting starts. */
	public static void waitBegin() {
		if (!timing) {
			return;
		}
		long now = System.nanoTime();
		if (encoding) {
			encoding = false;
			encodeNs += now - encodeStart;
		}
		waiting = true;
		waitStart = now;
	}

	public static void waitEnd() {
		if (waiting) {
			waiting = false;
			waitNs += System.nanoTime() - waitStart;
		}
	}

	/** Minecraft.tick() HEAD: one of the game's updates, run between two frames when one is due. */
	public static void tickBegin() {
		if (timing) {
			ticking = true;
			tickStart = System.nanoTime();
		}
	}

	public static void tickEnd() {
		if (ticking) {
			ticking = false;
			tickNs += System.nanoTime() - tickStart;
			ticks++;
		}
	}

	/** Before GpuSurface.acquireNextTexture() or present() in Minecraft.renderFrame. */
	public static void surfaceBegin() {
		if (timing) {
			atSurface = true;
			surfaceStart = System.nanoTime();
		}
	}

	public static void surfaceEnd() {
		if (atSurface) {
			atSurface = false;
			surfaceNs += System.nanoTime() - surfaceStart;
		}
	}

	/** A direct draw call in a Vulkan render pass, holding this many draws. */
	public static void countDraws(int count) {
		if (timing) {
			draws += count;
		}
	}

	/** An indirect draw call: the GPU reads this many draws from a buffer, and Metal runs each of them. */
	public static void countIndirectDraws(int count) {
		if (timing) {
			draws += count;
			indirectDraws += count;
		}
	}

	public static void countRenderPass() {
		if (timing) {
			passes++;
		}
	}

	/** A buffer or texture upload or copy. */
	public static void countCopy() {
		if (timing) {
			copies++;
		}
	}

	public static void countClear() {
		if (timing) {
			clears++;
		}
	}

	/**
	 * The parts of Etna's shadow setup, for EtnaShadows to report each frame, in nanoseconds: finding the caster
	 * candidates (scan), vanilla's preparation of their draws (prepare), recording the shadow pass (record) and
	 * writing the shadow uniforms (ubo). Once they come, the log splits "shadow setup" into them.
	 */
	public static void shadowScan(long ns) {
		shadowPart(0, ns);
	}

	public static void shadowPrepare(long ns) {
		shadowPart(1, ns);
	}

	public static void shadowRecord(long ns) {
		shadowPart(2, ns);
	}

	public static void shadowUbo(long ns) {
		shadowPart(3, ns);
	}

	private static void shadowPart(int part, long ns) {
		if (timing) {
			shadowPartNs[part] += ns;
		}
	}

	/**
	 * A frame's GPU time per part ({@link EtnaGpuTimer#PASSES}) has come back. It goes to the log and to the
	 * benchmark step being measured; each keeps it only if the frame started inside its window.
	 */
	static void gpuFrame(long frame, long[] passNs, long totalNs) {
		log.addGpu(frame, passNs, totalNs);
		Stats bench = EtnaBenchmark.measuring();
		if (bench != null) {
			bench.addGpu(frame, passNs, totalNs);
		}
	}

	/** Whether the profiler stopped itself after an error (the benchmark can't run then, and its button is greyed out). */
	public static boolean broken() {
		return broken;
	}

	private static void startInterval(long now) {
		frameStart = now;
		renderDone = false;
		renderNs = 0L;
		extracting = false;
		extractNs = 0L;
		submitting = false;
		submitNs = 0L;
		encoding = false;
		encodeNs = 0L;
		waiting = false;
		waitNs = 0L;
		ticking = false;
		tickNs = 0L;
		ticks = 0;
		atSurface = false;
		surfaceNs = 0L;
		draws = 0;
		indirectDraws = 0;
		passes = 0;
		copies = 0;
		clears = 0;
		Arrays.fill(shadowPartNs, 0L);
		levelDrawn = false;
		setupNs = 0L;
		castersRead = false;
		frustumDropped = -1;
		motion = 0.0F;
		menuOpen = false;
	}

	private static void logIfDue(long now, Minecraft mc) {
		if (EtnaConfig.profiling != EtnaConfig.Profiling.SUMMARY || EtnaBenchmark.running() || mc.level == null) {
			// Out of a world, or the benchmark writes its own table: start over when the log resumes.
			logStarted = false;
			headerDue = true;
			return;
		}
		if (!logStarted) {
			logStarted = true;
			log.reset(now);
			return;
		}
		if (now - log.start < LOG_PERIOD_NS) {
			return;
		}
		if (log.levelFrames > 0) {
			if (headerDue) {
				headerDue = false;
				EtnaClient.LOGGER.info("[Etna] Performance log on: {}; {}. {}", setup(mc), EtnaGpuTimer.describe(),
					"Every 5 s, per frame: frames per second; time between frames (mean and 95th percentile, also without"
						+ " the frames that ran a game tick); CPU ms for the game's ticks, extract, render (Etna's shadow setup is"
						+ " part of it), getting and showing the window image (acquire+present) and the game's own frame time;"
						+ " submit, split into encode (MoltenVK turning the frame into Metal commands) and wait (for the GPU);"
						+ " GPU ms per part: shadow = Etna's shadow map, level = the world, world = Etna's ambient occlusion and"
						+ " anti-aliasing, hand, final = Etna's bloom and colour grade plus vanilla's effects, gui = the HUD and the"
						+ " copy to the window; draws, passes, copies and clears; garbage collection pauses; shadow casters"
						+ " kept/tested and the indices they draw");
			}
			EtnaClient.LOGGER.info(summaryLine(log, mc));
		}
		log.reset(now);
	}

	private static String summaryLine(Stats s, Minecraft mc) {
		StringBuilder line = new StringBuilder(512);
		line.append(String.format(Locale.ROOT, "[Etna] 5s: %.0f fps (game %d) | frame interval %.1f ms (p95 %.1f",
			s.fps(), mc.getFps(), s.meanMs(s.intervalNs), s.p95Ms()));
		if (s.tickFrames > 0 && s.calmFrames() >= FEW_CALM_FRAMES) {
			line.append(String.format(Locale.ROOT, ", without tick frames %.1f", s.calmP95Ms()));
		}
		line.append(") | CPU ms:");
		if (s.ticks > 0) {
			line.append(String.format(Locale.ROOT, " ticks %.1f (%d ticks, %.1f ms each),", s.meanMs(s.tickNs), s.ticks,
				s.tickNs / 1.0e6 / s.ticks));
		}
		line.append(String.format(Locale.ROOT, " extract %.1f, render %.1f (shadow setup %.1f%s),",
			s.meanMs(s.extractNs), s.meanMs(s.renderNs), s.levelMeanMs(s.setupNs), s.shadowPartsText()));
		if (s.surfaceNs > 0L) {
			line.append(String.format(Locale.ROOT, " acquire+present %.1f,", s.meanMs(s.surfaceNs)));
		}
		line.append(String.format(Locale.ROOT, " game frame time %.1f | submit %.1f", s.meanMs(s.gameFrameNs), s.meanMs(s.submitNs)));
		if (s.submitSplit()) {
			line.append(String.format(Locale.ROOT, " (encode %.1f, wait for GPU %.1f)", s.meanMs(s.encodeNs), s.meanMs(s.waitNs)));
		}
		String gpu = s.gpuText();
		if (!gpu.isEmpty()) {
			line.append(" | ").append(gpu);
		}
		if (s.passes > 0L) {
			line.append(String.format(Locale.ROOT, " | per frame: draws %.0f (indirect %.0f), passes %.0f, copies %.0f, clears %.0f",
				s.perFrame(s.draws), s.perFrame(s.indirectDraws), s.perFrame(s.passes), s.perFrame(s.copies), s.perFrame(s.clears)));
		}
		line.append(" | ").append(s.gcText());
		line.append(" | casters ").append(s.castersText());
		if (s.lowestLimit < UNLIMITED_FPS) {
			line.append(" | the game limited fps to ").append(s.lowestLimit);
		}
		if (s.menuFrames > 0) {
			line.append(" | a menu was open");
		}
		line.append(" | ").append(settings()).append(", ").append(size(mc));
		return line.toString();
	}

	/** The settings that change the cost the most, in one short line. */
	static String settings() {
		if (!EtnaConfig.enabled) {
			return "Etna off";
		}
		String shadows = EtnaConfig.shadows
			? "shadows " + EtnaConfig.shadowResolution + "/" + Math.round(EtnaConfig.shadowDistance)
			: "shadows off";
		return title(EtnaConfig.quality) + " " + shadows + ", AA " + (EtnaConfig.antiAliasing ? "on" : "off") + ", " + title(EtnaConfig.profile);
	}

	/** Device, settings and anything else that changes what the numbers mean: printed before the first line. */
	static String setup(Minecraft mc) {
		StringBuilder text = new StringBuilder(256);
		try {
			var device = RenderSystem.getDevice().getDeviceInfo();
			text.append(device.name()).append(" (").append(device.backendName()).append(", ").append(device.driverInfo()).append("), ");
		} catch (RuntimeException e) {
			text.append("unknown device, ");
		}
		text.append("Etna ").append(EtnaConfig.enabled ? "on" : "off")
			.append(", quality ").append(title(EtnaConfig.quality))
			.append(" (shadows ").append(EtnaConfig.shadows ? EtnaConfig.shadowResolution + "/" + Math.round(EtnaConfig.shadowDistance) : "off")
			.append(", anti-aliasing ").append(EtnaConfig.antiAliasing ? "on" : "off")
			.append("), profile ").append(title(EtnaConfig.profile))
			.append(", half resolution ").append(EtnaConfig.halfResolution ? "on" : "off")
			.append(", shadow culling ").append(EtnaConfig.shadowCulling ? "on" : "off")
			.append(EtnaConfig.shadowGather ? "" : ", shadow gather off")
			.append(", render distance ").append(mc.options.getEffectiveRenderDistance()).append(" chunks")
			.append(", frame limit ").append(frameLimitText(mc.options.framerateLimit().get()))
			.append(", vsync ").append(mc.options.enableVsync().get() ? "on" : "off")
			.append(", ").append(display(mc))
			.append(", ").append(size(mc));
		String limiters = frameRateMods();
		if (!limiters.isEmpty()) {
			text.append(", mods that may cap fps: ").append(limiters);
		}
		return text.toString();
	}

	/**
	 * Windowed or fullscreen, exclusive (as set in the video options) or not, and the display's refresh rate. On
	 * macOS exclusive fullscreen alone took a sixth off the frame time with Etna off (no compositing by the
	 * system), so a table must say which it had.
	 */
	static String display(Minecraft mc) {
		try {
			var window = mc.getWindow();
			String mode = !mc.options.fullscreen().get() ? "windowed"
				: window.isExclusiveFullscreen() ? "exclusive fullscreen" : "fullscreen (not exclusive)";
			var video = window.getActiveVideoMode();
			return video == null ? mode : mode + ", display " + Math.round(video.getRefreshRate()) + " Hz";
		} catch (RuntimeException e) {
			return "display unknown";
		}
	}

	static String frameLimitText(int limit) {
		return limit >= UNLIMITED_FPS ? "unlimited" : Integer.toString(limit);
	}

	static String size(Minecraft mc) {
		return mc.gameRenderer.mainRenderTarget().width + "x" + mc.gameRenderer.mainRenderTarget().height;
	}

	/** Loaded mods whose id suggests they pace or cap frames: with one, fps says little and CPU times say more. */
	static String frameRateMods() {
		StringJoiner found = new StringJoiner(", ");
		try {
			for (var mod : FabricLoader.getInstance().getAllMods()) {
				String id = mod.getMetadata().getId();
				if (id.matches("(?i).*(fps|pacer|pacing|framerate|frame_?limit|vsync).*")) {
					found.add(id);
				}
			}
		} catch (RuntimeException e) {
			// Only a hint for the reader: never worth failing over.
		}
		return found.toString();
	}

	static String title(Enum<?> value) {
		String name = value.name();
		return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
	}

	/**
	 * Garbage collection pauses so far and the milliseconds they took, summed over the JVM's collectors, or
	 * {-1, -1} if the JVM doesn't say. ZGC and Shenandoah also report their concurrent cycles, which run beside
	 * the game: only the pauses stop it, so the cycles are left out. The first call loads the JVM's management
	 * classes, which takes a moment: the benchmark makes it before its first step.
	 */
	static long[] gcTotals() {
		long count = 0L;
		long ms = 0L;
		try {
			List<GarbageCollectorMXBean> beans = collectors;
			if (beans == null) {
				beans = ManagementFactory.getGarbageCollectorMXBeans();
				collectors = beans;
			}
			for (GarbageCollectorMXBean bean : beans) {
				if (bean.getName().contains("Cycles")) {
					continue;
				}
				count += Math.max(0L, bean.getCollectionCount());
				ms += Math.max(0L, bean.getCollectionTime());
			}
		} catch (RuntimeException | LinkageError e) {
			return new long[] {-1L, -1L};
		}
		return new long[] {count, ms};
	}

	private static void fail(RuntimeException e) {
		broken = true;
		timing = false;
		EtnaClient.LOGGER.error("Etna: the performance log failed, it is off for this session", e);
		EtnaBenchmark.stopAfterError();
	}

	/**
	 * Frame intervals summed over a stretch of time: five seconds of the log, or one benchmark step.
	 * Lives on the render thread.
	 */
	static final class Stats {
		private static final int MAX_SAMPLES = 8192;

		long start;
		/** The first frame of the stretch: GPU times of earlier frames, which come back late, are not booked here. */
		long firstFrame;
		int frames;
		long intervalNs;
		long renderNs;
		long extractNs;
		long submitNs;
		long encodeNs;
		long waitNs;
		long surfaceNs;
		long tickNs;
		int ticks;
		int tickFrames;
		long gameFrameNs;
		long draws;
		long indirectDraws;
		long passes;
		long copies;
		long clears;
		int levelFrames;
		long setupNs;
		final long[] shadowPartNs = new long[SHADOW_PARTS.length];
		int casterFrames;
		long candidates;
		long kept;
		long solidIndices;
		long cutoutIndices;
		int frustumFrames;
		long frustumDropped;
		int gpuFrames;
		final long[] gpuPassNs = new long[EtnaGpuTimer.PASSES.length];
		long gpuFrameNs;
		long gcCountAtStart;
		long gcMsAtStart;
		double motion;
		boolean turned;
		int menuFrames;
		int lowestLimit = Integer.MAX_VALUE;
		private boolean hasView;
		private final Matrix4f firstView = new Matrix4f();
		private final long[] intervals = new long[MAX_SAMPLES];
		private int stored;
		/** The intervals that ran no game tick. */
		private final long[] calmIntervals = new long[MAX_SAMPLES];
		private int calmStored;

		void reset(long now) {
			this.start = now;
			this.firstFrame = EtnaProfiler.frameNumber;
			this.frames = 0;
			this.intervalNs = 0L;
			this.renderNs = 0L;
			this.extractNs = 0L;
			this.submitNs = 0L;
			this.encodeNs = 0L;
			this.waitNs = 0L;
			this.surfaceNs = 0L;
			this.tickNs = 0L;
			this.ticks = 0;
			this.tickFrames = 0;
			this.gameFrameNs = 0L;
			this.draws = 0L;
			this.indirectDraws = 0L;
			this.passes = 0L;
			this.copies = 0L;
			this.clears = 0L;
			this.levelFrames = 0;
			this.setupNs = 0L;
			Arrays.fill(this.shadowPartNs, 0L);
			this.casterFrames = 0;
			this.candidates = 0L;
			this.kept = 0L;
			this.solidIndices = 0L;
			this.cutoutIndices = 0L;
			this.frustumFrames = 0;
			this.frustumDropped = 0L;
			this.gpuFrames = 0;
			Arrays.fill(this.gpuPassNs, 0L);
			this.gpuFrameNs = 0L;
			long[] gc = gcTotals();
			this.gcCountAtStart = gc[0];
			this.gcMsAtStart = gc[1];
			this.motion = 0.0;
			this.turned = false;
			this.menuFrames = 0;
			this.lowestLimit = Integer.MAX_VALUE;
			this.hasView = false;
			this.stored = 0;
			this.calmStored = 0;
		}

		/** Books the interval that just ended, with what the profiler saw during it. */
		void add(long interval, Minecraft mc) {
			this.frames++;
			this.intervalNs += interval;
			if (this.stored < MAX_SAMPLES) {
				this.intervals[this.stored++] = interval;
			}
			this.renderNs += EtnaProfiler.renderNs;
			this.extractNs += EtnaProfiler.extractNs;
			this.submitNs += EtnaProfiler.submitNs;
			this.encodeNs += EtnaProfiler.encodeNs;
			this.waitNs += EtnaProfiler.waitNs;
			this.surfaceNs += EtnaProfiler.surfaceNs;
			this.tickNs += EtnaProfiler.tickNs;
			this.ticks += EtnaProfiler.ticks;
			if (EtnaProfiler.ticks > 0) {
				this.tickFrames++;
			} else if (this.calmStored < MAX_SAMPLES) {
				this.calmIntervals[this.calmStored++] = interval;
			}
			this.draws += EtnaProfiler.draws;
			this.indirectDraws += EtnaProfiler.indirectDraws;
			this.passes += EtnaProfiler.passes;
			this.copies += EtnaProfiler.copies;
			this.clears += EtnaProfiler.clears;
			// Set by the game after the frame is recorded and blitted, before submit: this interval's frame.
			this.gameFrameNs += mc.getFrameTimeNs();
			this.lowestLimit = Math.min(this.lowestLimit, EtnaProfiler.frameLimit);
			if (EtnaProfiler.menuOpen) {
				this.menuFrames++;
			}
			if (EtnaProfiler.levelDrawn) {
				this.levelFrames++;
				this.setupNs += EtnaProfiler.setupNs;
				for (int i = 0; i < this.shadowPartNs.length; i++) {
					this.shadowPartNs[i] += EtnaProfiler.shadowPartNs[i];
				}
				this.motion += EtnaProfiler.motion;
				if (!this.hasView) {
					this.firstView.set(frameView);
					this.hasView = true;
				} else if (!frameView.equals(this.firstView, TURN_LIMIT)) {
					this.turned = true;
				}
			}
			if (EtnaProfiler.castersRead) {
				this.casterFrames++;
				this.candidates += EtnaProfiler.candidates;
				this.kept += EtnaProfiler.kept;
				this.solidIndices += EtnaProfiler.solidIndices;
				this.cutoutIndices += EtnaProfiler.cutoutIndices;
				if (EtnaProfiler.frustumDropped >= 0) {
					this.frustumFrames++;
					this.frustumDropped += EtnaProfiler.frustumDropped;
				}
			}
		}

		/** Books one frame's GPU times, if the frame started inside this stretch. */
		void addGpu(long frame, long[] passNs, long totalNs) {
			if (frame < this.firstFrame) {
				return;
			}
			this.gpuFrames++;
			for (int i = 0; i < this.gpuPassNs.length; i++) {
				this.gpuPassNs[i] += passNs[i];
			}
			this.gpuFrameNs += totalNs;
		}

		double fps() {
			return this.intervalNs > 0L ? this.frames * 1.0e9 / this.intervalNs : 0.0;
		}

		/** Mean per frame of a sum, in milliseconds. */
		double meanMs(long sumNs) {
			return this.frames > 0 ? sumNs / 1.0e6 / this.frames : 0.0;
		}

		/** Mean per frame that drew the world, in milliseconds. */
		double levelMeanMs(long sumNs) {
			return this.levelFrames > 0 ? sumNs / 1.0e6 / this.levelFrames : 0.0;
		}

		/** Mean per frame of a count. */
		double perFrame(long count) {
			return this.frames > 0 ? (double) count / this.frames : 0.0;
		}

		/** Mean per frame whose GPU times came back, in milliseconds. */
		double gpuMeanMs(long sumNs) {
			return this.gpuFrames > 0 ? sumNs / 1.0e6 / this.gpuFrames : 0.0;
		}

		double p95Ms() {
			return p95Ms(this.intervals, this.stored);
		}

		/** The 95th percentile of the intervals that ran no game tick. */
		double calmP95Ms() {
			return p95Ms(this.calmIntervals, this.calmStored);
		}

		int calmFrames() {
			return this.calmStored;
		}

		private static double p95Ms(long[] values, int count) {
			if (count == 0) {
				return 0.0;
			}
			long[] sorted = Arrays.copyOf(values, count);
			Arrays.sort(sorted);
			int index = Math.min(count - 1, (int) Math.ceil(0.95 * count) - 1);
			return sorted[Math.max(index, 0)] / 1.0e6;
		}

		/** Whether the Vulkan backend's submit was split into encode and wait (the hooks found their places). */
		boolean submitSplit() {
			return this.encodeNs > 0L || this.waitNs > 0L;
		}

		/**
		 * ": scan a, prepare b, record c, ubo d", once EtnaShadows reports the parts of its setup; empty until
		 * then. The rest of the setup (light, resources, dark spaces) is in none of them.
		 */
		String shadowPartsText() {
			long total = 0L;
			for (long ns : this.shadowPartNs) {
				total += ns;
			}
			if (total == 0L) {
				return "";
			}
			StringJoiner parts = new StringJoiner(", ", ": ", "");
			for (int i = 0; i < SHADOW_PARTS.length; i++) {
				parts.add(String.format(Locale.ROOT, "%s %.1f", SHADOW_PARTS[i], this.levelMeanMs(this.shadowPartNs[i])));
			}
			return parts.toString();
		}

		/** "GPU ms: shadow a, level b, ... = frame g", or empty when the GPU can't be timed. */
		String gpuText() {
			if (!EtnaGpuTimer.available()) {
				return "";
			}
			if (this.gpuFrames == 0) {
				return this.levelFrames > 0 ? "GPU ms: no frame came back yet" : "";
			}
			StringJoiner parts = new StringJoiner(", ", "GPU ms: ", "");
			for (int i = 0; i < EtnaGpuTimer.PASSES.length; i++) {
				parts.add(String.format(Locale.ROOT, "%s %.1f", EtnaGpuTimer.PASSES[i], this.gpuMeanMs(this.gpuPassNs[i])));
			}
			StringBuilder text = new StringBuilder(parts.toString());
			text.append(String.format(Locale.ROOT, " = frame %.1f", this.gpuMeanMs(this.gpuFrameNs)));
			if (this.gpuFrames * 4 * EtnaGpuTimer.EVERY < this.levelFrames * 3) {
				text.append(" (").append(this.gpuFrames).append(" of ").append(this.levelFrames).append(" frames timed)");
			}
			return text.toString();
		}

		/** Garbage collection pauses since the stretch began, or -1 if the JVM doesn't say. */
		long collections() {
			long count = gcTotals()[0];
			return count < 0L || this.gcCountAtStart < 0L ? -1L : count - this.gcCountAtStart;
		}

		String gcText() {
			long[] gc = gcTotals();
			if (gc[0] < 0L || this.gcCountAtStart < 0L) {
				return "GC n/a";
			}
			long count = gc[0] - this.gcCountAtStart;
			return count == 0L ? "GC none" : "GC " + count + " (" + (gc[1] - this.gcMsAtStart) + " ms)";
		}

		String castersText() {
			if (this.casterFrames == 0) {
				return "n/a";
			}
			int n = this.casterFrames;
			String text = Math.round((double) this.kept / n) + "/" + Math.round((double) this.candidates / n)
				+ " (solid " + count((double) this.solidIndices / n) + " idx, cutout " + count((double) this.cutoutIndices / n) + " idx)";
			if (this.frustumFrames > 0) {
				text += ", frustum dropped " + Math.round((double) this.frustumDropped / this.frustumFrames);
			}
			return text;
		}

		/** Casters kept/tested in short, for the benchmark table. */
		private String castersShort() {
			if (this.casterFrames == 0) {
				return "-";
			}
			String text = Math.round((double) this.kept / this.casterFrames) + "/" + Math.round((double) this.candidates / this.casterFrames);
			if (this.frustumFrames > 0) {
				text += " (frustum dropped " + Math.round((double) this.frustumDropped / this.frustumFrames) + ")";
			}
			return text;
		}

		/** The step's figures, kept after the stats are reused. */
		Result result() {
			double[] gpuPassMs = new double[this.gpuPassNs.length];
			for (int i = 0; i < gpuPassMs.length; i++) {
				gpuPassMs[i] = this.gpuMeanMs(this.gpuPassNs[i]);
			}
			return new Result(this.frames, this.fps(), this.meanMs(this.intervalNs), this.p95Ms(), this.meanMs(this.renderNs),
				this.levelMeanMs(this.setupNs), this.meanMs(this.extractNs), this.meanMs(this.submitNs), this.submitSplit(),
				this.meanMs(this.encodeNs), this.meanMs(this.waitNs), this.passes > 0L, this.perFrame(this.draws), this.perFrame(this.passes),
				this.perFrame(this.copies), this.castersShort(), this.motion, this.turned, this.lowestLimit, this.levelFrames,
				this.gpuFrames, gpuPassMs, this.gpuMeanMs(this.gpuFrameNs), this.collections());
		}

		private static String count(double n) {
			if (n >= 1.0e6) {
				return String.format(Locale.ROOT, "%.1fM", n / 1.0e6);
			}
			if (n >= 1.0e3) {
				return String.format(Locale.ROOT, "%.0fK", n / 1.0e3);
			}
			return Long.toString(Math.round(n));
		}
	}

	/**
	 * One benchmark step's figures: times in milliseconds per frame, counts per frame. gpuPassMs follows
	 * {@link EtnaGpuTimer#PASSES} and, like gpuFrameMs, means nothing when gpuFrames is 0. collections is -1
	 * when the JVM doesn't report its garbage collections.
	 */
	record Result(int frames, double fps, double intervalMs, double p95Ms, double renderMs, double setupMs, double extractMs,
			double submitMs, boolean submitSplit, double encodeMs, double waitMs, boolean counted, double draws, double passes,
			double copies, String casters, double motion, boolean turned, int lowestLimit, int levelFrames, int gpuFrames,
			double[] gpuPassMs, double gpuFrameMs, long collections) {
	}
}
