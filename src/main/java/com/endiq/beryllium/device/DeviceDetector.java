package com.endiq.beryllium.device;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.management.ManagementFactory;

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

		}
		return System.getProperty("os.arch", "unknown CPU");
	}

	private static long readTotalRamBytes() {
		try {
			Object osBean = ManagementFactory.getOperatingSystemMXBean();
			if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
				return sunBean.getTotalMemorySize();
			}
		} catch (Throwable ignored) {

		}
		return -1L;
	}
}
