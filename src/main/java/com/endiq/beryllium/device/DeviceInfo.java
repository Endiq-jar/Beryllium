package com.endiq.beryllium.device;

/**
 * Best-effort snapshot of the host device, gathered without needing a GL context.
 * GPU/display information lives separately in {@code GpuInfo} (client-only, requires
 * an active OpenGL context — see {@code GpuDetector}).
 *
 * @param totalRamBytes -1 if the JVM does not expose {@code com.sun.management}
 *                      (some Android-targeted JVM builds omit it); never crashes.
 */
public record DeviceInfo(
	String cpuModel,
	int cpuCores,
	String osName,
	String osVersion,
	String osArch,
	String javaVersion,
	long maxHeapBytes,
	long totalRamBytes
) {
	public double maxHeapGigabytes() {
		return maxHeapBytes / (1024.0 * 1024.0 * 1024.0);
	}

	public double totalRamGigabytes() {
		return totalRamBytes < 0 ? -1.0 : totalRamBytes / (1024.0 * 1024.0 * 1024.0);
	}
}
