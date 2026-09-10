package com.endiq.beryllium.compat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.device.DeviceDetector;
import com.endiq.beryllium.device.DeviceInfo;
import com.endiq.beryllium.util.BerylliumLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Phase 14 — the compatibility core's FPS layer.
 *
 * <p>The 1.21.4 renderer profile gets Beryllium's culling, voxel-shape and
 * scheduling features because those hook targets have been verified against that
 * exact build. Every other covered release ships this compatibility core instead:
 * no mixins, no renderer hooks, no game-class linkage during startup. The result
 * was launch-safe but FPS-neutral — and the hardware Beryllium exists for (Android
 * phones running a GL translation layer) mostly is not on 1.21.4.
 *
 * <p>This class closes that gap with the one category of optimization that needs
 * no version-specific knowledge at all: <b>vanilla option tuning through
 * reflection</b>. Options live on a stable public field ({@code Minecraft.options})
 * and expose a one-argument {@code set} method; everything else — which option
 * fields exist, what the option instance looks like internally — is resolved by
 * name at runtime and skipped if it does not resolve. No block of this class names
 * a Minecraft type at compile time, so it cannot fail to link, cannot throw at
 * class-load, and cannot make a launcher crash. Worst case on an unrecognized
 * build: every individual option write is skipped and the log says so.
 *
 * <p><b>Why a background thread.</b> {@code onInitializeClient} runs before the
 * client object (and therefore the options object) exists. Rather than hooking a
 * lifecycle event — which would need Fabric API, which the core deliberately does
 * not depend on — a single daemon thread polls for the client instance for up to
 * {@link #WAIT_LIMIT_SECONDS} seconds, then applies the preset once and exits. The
 * poll is a reflective {@code getInstance()} read, costs nothing measurable, and
 * the thread is a daemon so it can never hold the JVM open.
 *
 * <p><b>Presets.</b> {@code compatPreset} in beryllium.json selects the trade-off:
 * <ul>
 *   <li>{@code maxfps} (default) — only the options that cost frames <em>without</em>
 *       changing what the game looks like: VSync off, framerate limit unlocked,
 *       simulation distance at vanilla's minimum. Every visual setting is left
 *       exactly as configured.</li>
 *   <li>{@code mobile} — additionally applies the weak-device visual trade-offs
 *       (particles minimal, clouds off, entity shadows off, biome blend off, view
 *       bobbing off) and caps render distance per detected device tier. This is
 *       the right choice for a phone that is fill-rate limited.</li>
 *   <li>{@code off} — no writes at all.</li>
 * </ul>
 * Whichever preset runs, its changes are written to options.txt (so they are
 * visible in the video settings screen and reversible there), and the run is
 * recorded in {@code compatAutoTuneApplied} so it only happens once.
 */
public final class CompatTuner {
	private CompatTuner() {
	}

	/** How long the daemon thread waits for the client/options object to exist. */
	private static final int WAIT_LIMIT_SECONDS = 180;

	/** Vanilla's lowest selectable framerate limit. */
	private static final int VANILLA_MIN_FRAMERATE = 10;

	/** The virtual maximum vanilla's framerate slider uses for "unlimited". */
	private static final int FRAMERATE_UNLIMITED = 260;

	/** Vanilla's lowest simulation-distance slider value. */
	private static final int SIMULATION_DISTANCE_MIN = 5;

	/** Preset name for the non-visual, maximum-FPS-with-fancy-visuals tuning. */
	public static final String PRESET_MAXFPS = "maxfps";

	/** Preset name for the weak-device tuning (visual trade-offs included). */
	public static final String PRESET_MOBILE = "mobile";

	/** Preset name that disables the tuner. */
	public static final String PRESET_OFF = "off";

	/** Runs the tuner on a daemon thread. Safe to call before the client exists. */
	public static void schedule() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.compatAutoTune) {
			return;
		}
		if (PRESET_OFF.equalsIgnoreCase(config.compatPreset)) {
			BerylliumLog.info("[BERYLLIUM-COMPAT] compatAutoTune is on but compatPreset is \"off\";"
				+ " no vanilla options will be changed.");
			return;
		}

		Thread thread = new Thread(CompatTuner::waitForClientThenTune, "beryllium-compat-tuner");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	private static void waitForClientThenTune() {
		BerylliumConfig config = Beryllium.config();
		if (config == null) {
			return;
		}
		if (config.compatAutoTuneApplied) {
			BerylliumLog.info("[BERYLLIUM-COMPAT] compat tuning already applied on a previous"
				+ " launch (set compatAutoTuneApplied=false in beryllium.json to re-apply).");
			return;
		}

		Object options = null;
		for (int waited = 0; waited < WAIT_LIMIT_SECONDS; waited++) {
			options = findOptionsObject();
			if (options != null) {
				break;
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		if (options == null) {
			BerylliumLog.warn("[BERYLLIUM-COMPAT] client options did not appear within "
				+ WAIT_LIMIT_SECONDS + "s; skipping compat tuning for this launch.");
			return;
		}

		apply(config, options);
	}

	/** Finds the live options object without linking {@code Minecraft} at compile time. */
	private static Object findOptionsObject() {
		try {
			Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
			Method getInstance = minecraftClass.getMethod("getInstance");
			Object minecraft = getInstance.invoke(null);
			if (minecraft == null) {
				return null;
			}
			// Stable across the covered range: a public field named "options".
			for (Class<?> c = minecraft.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				try {
					Field field = c.getDeclaredField("options");
					field.setAccessible(true);
					return field.get(minecraft);
				} catch (NoSuchFieldException e) {
					// keep walking up
				}
			}
		} catch (Throwable t) {
			// Client not up yet, or a build whose shape we cannot read; retry/abort.
		}
		return null;
	}

	private static void apply(BerylliumConfig config, Object options) {
		boolean mobilePreset = PRESET_MOBILE.equalsIgnoreCase(config.compatPreset);
		DeviceInfo device = DeviceDetector.detect();

		BerylliumLog.info("[BERYLLIUM-COMPAT] Applying the \"" + config.compatPreset
			+ "\" preset on this release (compatibility core; reflection-only option tuning, no"
			+ " renderer hooks). Device: " + device.cpuModel() + ", " + device.cpuCores()
			+ " threads, " + (device.totalRamGigabytes() > 0
				? device.totalRamGigabytes() + " GB RAM" : "RAM unknown"));

		int applied = 0;

		// --- Non-visual frame costs: always part of both presets. ---
		applied += write(options, "enableVsync", Boolean.FALSE, "enableVsync = false") ? 1 : 0;
		applied += write(options, "framerateLimit", FRAMERATE_UNLIMITED,
			"framerateLimit = " + FRAMERATE_UNLIMITED + " (unlimited)") ? 1 : 0;
		applied += write(options, "simulationDistance", SIMULATION_DISTANCE_MIN,
			"simulationDistance = " + SIMULATION_DISTANCE_MIN
				+ " (vanilla minimum; visual render distance is unchanged)") ? 1 : 0;

		// --- Visual trade-offs: only in the mobile preset. ---
		if (mobilePreset) {
			Object particles = enumConstant("net.minecraft.client.particles.ParticleMode", "MINIMAL");
			if (particles == null) {
				particles = enumConstant("net.minecraft.core.particles.ParticleTypes$ParticleMode", "MINIMAL");
			}
			if (particles != null) {
				applied += write(options, "particles", particles, "particles = MINIMAL") ? 1 : 0;
			} else {
				BerylliumLog.info("[BERYLLIUM-COMPAT]   - particles: skipped (enum not found).");
			}
			Object clouds = enumConstant("net.minecraft.client.option.CloudRenderMode", "OFF");
			if (clouds != null) {
				applied += write(options, "clouds", clouds, "clouds = OFF") ? 1 : 0;
			} else {
				BerylliumLog.info("[BERYLLIUM-COMPAT]   - clouds: skipped (enum not found).");
			}
			applied += write(options, "entityShadows", Boolean.FALSE, "entityShadows = false") ? 1 : 0;
			applied += write(options, "biomeBlend", Boolean.FALSE, "biomeBlend = false") ? 1 : 0;
			applied += write(options, "bobView", Boolean.FALSE, "bobView = false") ? 1 : 0;

			// Render distance is the single biggest lever on a fill-rate limited phone.
			// Tiering: known-to-be-small RAM (< 6 GB), or unknown RAM with few cores,
			// gets the tighter cap; anything else gets the moderate one. Never a
			// silent guess at "this is a bad device" — both caps are logged.
			double ramGb = device.totalRamGigabytes();
			boolean smallDevice = (ramGb > 0 && ramGb < 6.0) || (ramGb <= 0 && device.cpuCores() <= 4);
			int capped = smallDevice ? 6 : 10;
			applied += write(options, "renderDistance", capped,
				"renderDistance = " + capped + " chunks (capped for this device)") ? 1 : 0;
		}

		if (applied > 0) {
			invokeNoArg(options, "save");
			config.compatAutoTuneApplied = true;
			config.save();
			BerylliumLog.info("[BERYLLIUM-COMPAT] Applied " + applied + " option(s); saved to"
				+ " options.txt (revert any of them in the video settings screen).");
			if (!mobilePreset) {
				BerylliumLog.info("[BERYLLIUM-COMPAT] Visual settings (graphics mode, smooth lighting,"
					+ " clouds, particles, entity shadows, biome blending) were NOT changed."
					+ " Set compatPreset=\"mobile\" in beryllium.json for the extra weak-device"
					+ " trade-offs, or \"off\" to disable this tuner.");
			}
		} else {
			BerylliumLog.warn("[BERYLLIUM-COMPAT] No option could be written on this build (options"
				+ " API mismatch); leaving vanilla settings untouched.");
		}
	}

	private static boolean write(Object options, String fieldName, Object value, String logLine) {
		if (set(options, fieldName, value)) {
			BerylliumLog.info("[BERYLLIUM-COMPAT]   - " + logLine);
			return true;
		}
		BerylliumLog.info("[BERYLLIUM-COMPAT]   - " + fieldName
			+ ": skipped (option not found or not settable on this build).");
		return false;
	}

	private static boolean set(Object options, String fieldName, Object value) {
		try {
			Object instance = fieldValue(options, fieldName);
			if (instance == null) {
				return false;
			}
			for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (Method method : c.getDeclaredMethods()) {
					if (method.getName().equals("set") && method.getParameterCount() == 1) {
						method.setAccessible(true);
						method.invoke(instance, value);
						return true;
					}
				}
			}
			return false;
		} catch (Throwable t) {
			return false;
		}
	}

	private static Object fieldValue(Object target, String fieldName) {
		if (target == null) {
			return null;
		}
		try {
			for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				try {
					Field field = c.getDeclaredField(fieldName);
					if (Modifier.isStatic(field.getModifiers())) {
						continue;
					}
					field.setAccessible(true);
					return field.get(target);
				} catch (NoSuchFieldException e) {
					// keep walking up
				}
			}
		} catch (Throwable ignored) {
			// caller degrades
		}
		return null;
	}

	private static Object enumConstant(String className, String constantName) {
		try {
			Class<?> clazz = Class.forName(className);
			for (Object constant : clazz.getEnumConstants()) {
				if (constantName.equals(((Enum<?>) constant).name())) {
					return constant;
				}
			}
		} catch (Throwable ignored) {
			// try the next candidate name
		}
		return null;
	}

	private static void invokeNoArg(Object target, String name) {
		if (target == null) {
			return;
		}
		try {
			for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				try {
					Method method = c.getDeclaredMethod(name);
					method.setAccessible(true);
					method.invoke(target);
					return;
				} catch (NoSuchMethodException e) {
					// keep walking up
				}
			}
		} catch (Throwable ignored) {
			// best-effort persistence
		}
	}
}
