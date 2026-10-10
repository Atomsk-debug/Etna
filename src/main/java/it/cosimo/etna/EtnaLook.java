package it.cosimo.etna;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Live values for the post effects: the camera matrices the world pass needs and the settings menu.
 * Post effect uniforms normally come fixed from the JSON, in one buffer per pass that nothing can write
 * to afterwards. Every pass that declares an "EtnaSettings" block gets this one shared, writable buffer
 * instead (PostPassMixin), and it is rewritten once per frame: every pass reads the same values, so one
 * copy and one barrier serve them all instead of one per pass.
 * The layout is the EtnaSettings block in assets/etna/shaders/include/settings.glsl.
 */
public final class EtnaLook {
	public static final String BLOCK = "EtnaSettings";
	/** std140: three mat4s and twenty-four vec4s. */
	public static final int SIZE = 3 * 64 + 24 * 16;
	/** Uniform buffer that the command encoder may write to. */
	public static final int USAGE = 136;

	private static @Nullable GpuBuffer shared;
	/** How many live post passes hold the shared buffer: the last one to close frees it. */
	private static int users;

	private EtnaLook() {
	}

	/** A new post pass declared the block: hand it the shared buffer, made on first use. */
	public static GpuBuffer acquireBuffer() {
		GpuBuffer buffer = shared;
		if (buffer == null || buffer.isClosed()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				buffer = RenderSystem.getDevice().createBuffer(() -> "Etna / " + BLOCK, USAGE, data(stack));
			}
			shared = buffer;
			users = 0;
		}
		users++;
		return buffer;
	}

	/**
	 * A post pass that holds the shared buffer is closing (its chain was reloaded or dropped). The buffer
	 * stays open while other passes use it; the passes of the chains loaded next may already hold it.
	 */
	public static void releaseBuffer(GpuBuffer buffer) {
		if (buffer != shared) {
			// A buffer from before the shared one was replaced: it is closed already.
			return;
		}
		users--;
		if (users <= 0) {
			buffer.close();
			shared = null;
			users = 0;
		}
	}

	/**
	 * Once per frame, while no render pass is open. With Etna off no chain runs, but the chains stay loaded and
	 * so does the buffer: writing it would still cost a copy and a full barrier. activeEffect and temporal lag a
	 * tick behind the switch, and the chains still run while they do.
	 */
	public static void upload() {
		// Every frame, so the eyes keep up with the light even while the chains don't run.
		EtnaEye.update();
		GpuBuffer buffer = shared;
		if (buffer == null || buffer.isClosed()
			|| (!EtnaConfig.enabled && EtnaClient.activeEffect() == null && !EtnaFrame.temporal())) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data(stack));
		}
	}

	private static ByteBuffer data(MemoryStack stack) {
		Matrix4f m = new Matrix4f();
		Vector3f sun = EtnaShadows.sunDirection();
		Std140Builder builder = Std140Builder.onStack(stack, SIZE)
			.putMat4f(EtnaFrame.inverseViewProjection(m))
			.putMat4f(EtnaFrame.viewProjection(m))
			.putMat4f(EtnaFrame.previousViewProjection(m))
			.putVec4(new Vector4f(EtnaFrame.motionX(), EtnaFrame.motionY(), EtnaFrame.motionZ(), EtnaFrame.historyValid() ? 1.0F : 0.0F))
			.putVec4(new Vector4f(EtnaFrame.frame() % 4096, EtnaFrame.jitterX(), EtnaFrame.jitterY(), EtnaFrame.temporal() ? 1.0F : 0.0F))
			.putVec4(new Vector4f(EtnaConfig.brightness.value, EtnaConfig.contrast.value, EtnaConfig.saturation.value, EtnaConfig.vibrance.value))
			.putVec4(new Vector4f(EtnaConfig.warmth.value, EtnaConfig.enabled ? EtnaConfig.visibility.value : 0.0F, EtnaConfig.vignette.value,
				EtnaConfig.sharpen.value))
			// Glow.y and Glow.w: the bloom fog (0.8.0 WEATHER, EtnaAir).
			.putVec4(new Vector4f(EtnaConfig.bloom.value, EtnaAir.bloomFog(), EtnaConfig.bloomSize.value, EtnaAir.bloomFogReach()))
			.putVec4(EtnaSky.postShade(EtnaConfig.ambientOcclusionStrength()))
			.putVec4(new Vector4f(sun.x, sun.y, sun.z, EtnaShadows.lightStrength()))
			.putVec4(new Vector4f(EtnaConfig.skyGlow.value, EtnaConfig.atmosphere.value, EtnaShadows.rain(),
				EtnaShadows.sunWorld() ? (EtnaShadows.moonLight() ? 2.0F : 1.0F) : 0.0F));
		// Water, bytes 320-335.
		EtnaWater.writePost(builder);
		// Eye, bytes 336-351: eye adaptation (exposure and night vision).
		EtnaEye.writePost(builder);
		// Mist, MistColor, Shafts and AirSun, bytes 352-415.
		EtnaAir.writePost(builder);
		// Bytes 416-495: Mood0 .. Mood4, the mood filter.
		EtnaMood.writePost(builder);
		// Bytes 496-511: Motes, the fireflies and motes the last world pass draws.
		EtnaMotes.writePost(builder);
		// Bytes 512-527: WaterSsr (water reflections).
		EtnaWaterSsr.writePost(builder);
		// Bytes 528-575: Mood5 .. Mood7, the rest of the mood filter.
		EtnaMood.writePostTail(builder);
		return builder.get();
	}
}
