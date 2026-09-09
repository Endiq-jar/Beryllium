package com.endiq.beryllium.device;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/**
 * Detects non-GPU device information. Every lookup here is wrapped so a platform that
 * doesn't support a given API (e.g. no {@code /proc/cpuinfo}, no {@code com.sun.management})
 * degrades to an "unknown" placeholder instead of crashing mod initialization.
 */
public final class DeviceDetector {
	private DeviceDetector() {
	}

	public static DeviceInfo detect() {
		return new DeviceInfo(
			readCpuModel(),
			Runtime.getRuntime().availableProcessors(),
			System.getProperty("os.name", "unknown"),
			System.getProperty("os.version", "unknown"),
			System.getProperty("os.arch", "unknown"),
			System.getProperty("java.version", "unknown"),
			Runtime.getRuntime().maxMemory(),
			readTotalRamBytes()
		);
	}

	/**
	 * Reads the CPU model from {@code /proc/cpuinfo}, which is present on Linux and Android
	 * (the two platforms Beryllium actually targets). On any other platform, or if the file
	 * is missing/unreadable, falls back to the JVM's architecture string.
	 */
	private static String readCpuModel() {
		try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String lower = line.toLowerCase();
				if (lower.startsWith("model name") || lower.startsWith("hardware")) {
					int separator = line.indexOf(':');
					if (separator != -1 && separator + 1 < line.length()) {
						String value = line.substring(separator + 1).trim();
						if (!value.isEmpty()) {
							return value;
						}
					}
				}
			}
		} catch (Exception ignored) {
			// /proc/cpuinfo is unavailable on this platform (e.g. Windows) — fall through.
		}
		return System.getProperty("os.arch", "unknown CPU");
	}

	/**
	 * Total physical RAM, queried reflectively. Android launcher JVMs often do not ship
	 * {@code com.sun.management.OperatingSystemMXBean}; keeping that type out of this
	 * class's bytecode avoids a class-linkage failure before our defensive catch block
	 * could run. Modern JDKs call the method {@code getTotalMemorySize}, while older
	 * JDKs expose {@code getTotalPhysicalMemorySize}, so try both.
	 */
	private static long readTotalRamBytes() {
		try {
			Object osBean = ManagementFactory.getOperatingSystemMXBean();
			for (String methodName : new String[] {"getTotalMemorySize", "getTotalPhysicalMemorySize"}) {
				try {
					Method method = osBean.getClass().getMethod(methodName);
					Object value = method.invoke(osBean);
					if (value instanceof Number) {
						long bytes = ((Number) value).longValue();
						if (bytes > 0L) {
							return bytes;
						}
					}
				} catch (ReflectiveOperationException | SecurityException ignored) {
					// Try the other name, or report unknown below.
				}
			}
		} catch (Throwable ignored) {
			// Not present on every JVM distribution — treat as unknown, not fatal.
		}
		return -1L;
	}
}
