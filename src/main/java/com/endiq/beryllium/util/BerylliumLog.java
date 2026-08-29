package com.endiq.beryllium.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BerylliumLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("Beryllium");

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
