package com.endiq.beryllium.tick;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/**
 * A null-safe read-through view of the handful of config values the tick mixins touch on
 * hot paths.
 *
 * <p>Mixins run before/around vanilla code that can be entered from places where
 * Beryllium's own initialization has not happened (a world loading during startup, a
 * mixin firing on a thread that never saw mod init). Every accessor therefore falls back
 * to the documented default instead of dereferencing a null config, and every read is a
 * plain field load so it stays cheap enough for per-chunk/per-entity calls.
 */
public final class BerylliumConfigCache {
	private BerylliumConfigCache() {
	}

	private static BerylliumConfig config() {
		BerylliumConfig config = Beryllium.config();
		return config == null ? null : config;
	}

	private static boolean enabled() {
		BerylliumConfig config = config();
		return config != null && config.enabled;
	}

	public static boolean hopperThrottling() {
		BerylliumConfig config = config();
		return enabled() && config.hopperThrottling;
	}

	public static int hopperThrottleInterval() {
		BerylliumConfig config = config();
		if (config == null) {
			return 4;
		}
		return TickTimeGovernor.instance().scaleInterval(Math.max(1, config.hopperThrottleInterval));
	}

	public static int hopperIdleSamples() {
		BerylliumConfig config = config();
		return config == null ? 3 : Math.max(1, config.hopperIdleSamples);
	}

	public static boolean itemEntityThrottling() {
		BerylliumConfig config = config();
		return enabled() && config.itemEntityThrottling;
	}

	public static int itemEntityThrottleInterval() {
		BerylliumConfig config = config();
		return config == null ? 4 : Math.max(1, config.itemEntityThrottleInterval);
	}

	public static boolean asyncRandomTicks() {
		BerylliumConfig config = config();
		return enabled() && config.asyncRandomTicks;
	}

	public static int asyncRandomTickThreads() {
		BerylliumConfig config = config();
		return config == null ? 0 : config.asyncRandomTickThreads;
	}

	public static boolean parallelEntityTicking() {
		BerylliumConfig config = config();
		return enabled() && config.parallelEntityTicking;
	}

	public static int parallelEntityTickThreads() {
		BerylliumConfig config = config();
		return config == null ? 0 : config.parallelEntityTickThreads;
	}

	public static int parallelEntityTickMinEntities() {
		BerylliumConfig config = config();
		return config == null ? 32 : Math.max(2, config.parallelEntityTickMinEntities);
	}

	public static int parallelEntityTickChunkSize() {
		BerylliumConfig config = config();
		return config == null ? 8 : Math.max(1, config.parallelEntityTickChunkSize);
	}

	public static boolean tickGovernorEnabled() {
		BerylliumConfig config = config();
		return enabled() && config.tickGovernorEnabled;
	}

	public static double targetTickTimeMillis() {
		BerylliumConfig config = config();
		return config == null ? 45.0 : config.targetTickTimeMillis;
	}

	public static double tickGovernorMaxScale() {
		BerylliumConfig config = config();
		return config == null ? 4.0 : Math.max(1.0, config.tickGovernorMaxScale);
	}
}
