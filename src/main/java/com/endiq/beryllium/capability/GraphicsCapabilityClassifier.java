package com.endiq.beryllium.capability;

/**
 * Classifies already-detected device values into a {@link GraphicsCapabilityTier}. Pure
 * function, zero Minecraft/LWJGL dependency — the actual GL queries (GL_MAX_TEXTURE_SIZE
 * etc.) that feed this live in the client-only {@code GpuDetector}; this class only ever
 * sees plain numbers and strings, so it's fully unit-testable on its own.
 *
 * <p>Per spec section 15's explicit instruction ("do not classify devices purely by
 * marketing name"), the renderer string is only ever used as a small conservative
 * nudge, never as the primary signal — the primary signal is the actual queried limits
 * (texture size) plus CPU/RAM, which is real, checkable capacity rather than a name
 * lookup table.
 */
public final class GraphicsCapabilityClassifier {
	private GraphicsCapabilityClassifier() {
	}

	/**
	 * @param maxTextureSize GL_MAX_TEXTURE_SIZE, or &lt;=0 if detection failed/hasn't run yet
	 * @param rendererLowercase the GL_RENDERER string, lowercased (may be null)
	 * @param cpuCores logical CPU core count
	 * @param totalRamGigabytes total system RAM in GB, or a negative value if unknown
	 */
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
