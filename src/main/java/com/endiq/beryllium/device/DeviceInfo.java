package com.endiq.beryllium.device;

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
