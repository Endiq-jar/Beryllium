package com.endiq.beryllium.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Detects Android-hosted Java launchers without linking against Android or GLFW classes.
 *
 * <p>PojavLauncher, Zalith, TurtleLauncher and related launchers do not expose one
 * stable Java API we can depend on. Some report {@code os.name=Linux}, some expose an
 * Android VM property, and some only have Android's standard environment variables.
 * Detection therefore combines several harmless hints and is intentionally
 * conservative: an Android signal is enough to select the safe startup path, while a
 * desktop ARM Linux JVM is not treated as Android merely because of its architecture.
 */
public final class LauncherEnvironment {
	private static final String[] PROPERTY_KEYS = {
		"java.vm.name",
		"java.runtime.name",
		"java.vendor",
		"java.vendor.url",
		"java.vm.vendor",
		"os.name",
		"os.version",
		"os.arch",
		"pojav.launcher",
		"pojav.version",
		"zalith.launcher",
		"turtle.launcher",
		"turtlelauncher.version",
		"launcher.name"
	};

	private static final String[] ENVIRONMENT_KEYS = {
		"ANDROID_ROOT",
		"ANDROID_DATA",
		"ANDROID_BOOTLOGO",
		"POJAV_HOME",
		"POJAV_RENDERER",
		"ZALITH_HOME",
		"TURTLE_LAUNCHER",
		"TURTLELAUNCHER_HOME"
	};

	private final boolean androidRuntime;
	private final boolean knownLauncher;
	private final String description;

	private LauncherEnvironment(boolean androidRuntime, boolean knownLauncher, String description) {
		this.androidRuntime = androidRuntime;
		this.knownLauncher = knownLauncher;
		this.description = description;
	}

	public static LauncherEnvironment detect() {
		StringBuilder signals = new StringBuilder();
		boolean android = false;
		boolean launcher = false;

		for (String key : PROPERTY_KEYS) {
			String value = property(key);
			String normalized = normalize(value);
			if (normalized.isEmpty()) {
				continue;
			}

			if (containsAndroidMarker(normalized)) {
				android = true;
				appendSignal(signals, key + "=" + abbreviate(value));
			}
			if (containsLauncherMarker(normalized)) {
				launcher = true;
				appendSignal(signals, key + "=" + abbreviate(value));
			}
		}

		for (String key : ENVIRONMENT_KEYS) {
			String value = environment(key);
			if (value == null) {
				continue;
			}
			android = true;
			if (key.startsWith("POJAV") || key.startsWith("ZALITH") || key.startsWith("TURTLE")) {
				launcher = true;
			}
			appendSignal(signals, key);
		}

		// Android's standard locations are a useful fallback when a launcher sanitises
		// both properties and environment variables. Never load android.os.Build here:
		// a class probe itself has caused linkage trouble in some launcher JVMs.
		if (!android && (pathExists("/system/build.prop") || pathExists("/apex/com.android.runtime"))) {
			android = true;
			appendSignal(signals, "Android system path");
		}

		String description = signals.length() == 0 ? "no Android launcher markers" : signals.toString();
		return new LauncherEnvironment(android, launcher, description);
	}

	/** True for Android itself or a known Java launcher running on it. */
	public boolean isAndroidJavaLauncher() {
		return androidRuntime || knownLauncher;
	}

	public boolean isKnownLauncher() {
		return knownLauncher;
	}

	/** A compact diagnostic suitable for a startup log; it contains no user paths. */
	public String describe() {
		return description;
	}

	private static boolean containsAndroidMarker(String value) {
		return value.contains("android") || value.contains("dalvik") || value.contains("art runtime");
	}

	private static boolean containsLauncherMarker(String value) {
		return value.contains("pojav") || value.contains("zalith") || value.contains("turtlelauncher")
			|| value.contains("turtle launcher") || value.contains("turtle-launcher");
	}

	private static String property(String key) {
		try {
			return System.getProperty(key, "");
		} catch (SecurityException ignored) {
			return "";
		}
	}

	private static String environment(String key) {
		try {
			return System.getenv(key);
		} catch (SecurityException ignored) {
			return null;
		}
	}

	private static boolean pathExists(String value) {
		try {
			return Files.exists(Path.of(value));
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	private static String abbreviate(String value) {
		if (value == null) {
			return "";
		}
		return value.length() <= 48 ? value : value.substring(0, 45) + "...";
	}

	private static void appendSignal(StringBuilder signals, String signal) {
		if (signals.length() > 0) {
			signals.append(", ");
		}
		signals.append(signal);
	}
}
