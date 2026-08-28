package com.endiq.beryllium.capability;

public final class GraphicsCapabilityClassifier {
	private GraphicsCapabilityClassifier() {
	}

	public static GraphicsCapabilityTier classify(
		int maxTextureSize, String rendererLowercase, int cpuCores, double totalRamGigabytes
	) {
		// Detection failed entirely — be conservative rather than guessing upward.
		if (maxTextureSize <= 0) {
			return GraphicsCapabilityTier.COMPATIBILITY;
		}

		int score = 0;

		if (maxTextureSize >= 16384) {
			score += 2;
		} else if (maxTextureSize >= 8192) {
			score += 1;
		}

		if (cpuCores >= 8) {
			score += 2;
		} else if (cpuCores >= 6) {
			score += 1;
		}

		if (totalRamGigabytes >= 8.0) {
			score += 2;
		} else if (totalRamGigabytes >= 4.0) {
			score += 1;
		}

		if (isKnownMobileGpu(rendererLowercase)) {
			// A conservative nudge down: raw texture-size limits alone can overstate
			// real-world fill rate/throughput on some mobile chips relative to desktop
			// GPUs reporting the same limit.
			score -= 1;
		}

		if (score >= 5) {
			return GraphicsCapabilityTier.HIGH_END;
		}
		if (score >= 3) {
			return GraphicsCapabilityTier.ADVANCED;
		}
		if (score >= 1) {
			return GraphicsCapabilityTier.STANDARD;
		}
		return GraphicsCapabilityTier.COMPATIBILITY;
	}

	private static boolean isKnownMobileGpu(String rendererLowercase) {
		if (rendererLowercase == null) {
			return false;
		}
		return rendererLowercase.contains("adreno")
			|| rendererLowercase.contains("mali")
			|| rendererLowercase.contains("powervr")
			|| rendererLowercase.contains("xclipse");
	}
}
