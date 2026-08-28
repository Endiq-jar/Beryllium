package com.endiq.beryllium.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured, tag-prefixed logging for Beryllium. One backing logger keeps the log
 * file/console output grouped under a single "Beryllium" source, while the per-subsystem
 * methods keep messages easy to grep once more subsystems (chunking, caching, mobile
 * power management, compatibility) come online in later phases.
 *
 * <p>Nothing here logs on a per-frame or per-tick basis; callers are expected to log at
 * lifecycle boundaries (startup, config changes, state transitions) only.
 */
public final class BerylliumLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("Beryllium");

	/** Set from {@code BerylliumConfig} after load; gates debug()-level messages. */
	private static volatile boolean debugEnabled = false;

	private BerylliumLog() {
	}

	public static void setDebugEnabled(boolean enabled) {
		debugEnabled = enabled;
	}

	public static void info(String message) {
		LOGGER.info("[BERYLLIUM] {}", message);
	}

	public static void warn(String message) {
		LOGGER.warn("[BERYLLIUM] {}", message);
	}

	public static void error(String message, Throwable cause) {
		LOGGER.error("[BERYLLIUM] {}", message, cause);
	}

	/** Only emitted when {@code debugMode} is enabled in beryllium.json. */
	public static void debug(String message) {
		if (debugEnabled) {
			LOGGER.info("[BERYLLIUM-DEBUG] {}", message);
		}
	}

	public static void gpu(String message) {
		LOGGER.info("[BERYLLIUM-GPU] {}", message);
	}

	public static void chunk(String message) {
		LOGGER.info("[BERYLLIUM-CHUNK] {}", message);
	}

	public static void cache(String message) {
		LOGGER.info("[BERYLLIUM-CACHE] {}", message);
	}

	public static void mobile(String message) {
		LOGGER.info("[BERYLLIUM-MOBILE] {}", message);
	}

	public static void compat(String message) {
		LOGGER.info("[BERYLLIUM-COMPAT] {}", message);
	}
}
