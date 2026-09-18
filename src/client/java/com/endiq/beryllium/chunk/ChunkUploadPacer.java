package com.endiq.beryllium.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/**
 * Chunk upload pacing.
 *
 * <p>When a chunk section finishes compiling, its geometry has to be uploaded to the GPU.
 * Vanilla drains that queue whenever it likes, and it likes to drain it all at once: after
 * a fast fly, a big redstone flood or a world-load burst, one frame can be handed dozens of
 * uploads. The upload itself is a driver call, and a burst of them is a textbook frame
 * spike — visible as a hitch exactly when the player is moving.
 *
 * <p>The pacer gives each frame a small allowance (default 2, so a normal frame still
 * uploads promptly) and defers the rest to the following frames. Nothing is dropped, and
 * the queue cannot grow without bound: the moment a frame is cheap again, the allowance is
 * restored, and when frames are expensive the allowance shrinks to keep the hitch from
 * compounding rather than to stop progress.
 */
public final class ChunkUploadPacer {
	private static final long TARGET_FRAME_NANOS = 16_666_667L;

	private static final ChunkUploadPacer INSTANCE = new ChunkUploadPacer();

	private int budget;
	private int used;
	private long deferredTotal;

	private ChunkUploadPacer() {
	}

	public static ChunkUploadPacer instance() {
		return INSTANCE;
	}

	/**
	 * @param lastFrameNanos the previous frame's duration, or -1 if not yet known
	 */
	public void onFrameStart(long lastFrameNanos) {
		BerylliumConfig config = Beryllium.config();
		int configured = config == null ? 2 : Math.max(0, config.chunkUploadsPerFrame);
		if (configured == 0 || config == null || !config.enabled || !config.chunkUploadPacing
			|| Beryllium.isChunkOptimizationDeferredToOtherMod()) {
			// 0 (or the feature off) means "no pacing" — let vanilla drain as it pleases.
			budget = 0;
		} else if (lastFrameNanos > TARGET_FRAME_NANOS * 2L) {
			// A frame that already blew its budget: upload the bare minimum this frame.
			budget = 1;
		} else if (lastFrameNanos > TARGET_FRAME_NANOS) {
			budget = Math.max(1, configured / 2);
		} else {
			budget = configured;
		}
		used = 0;
	}

	/**
	 * @return true if an upload may proceed this frame
	 */
	public boolean allowUpload() {
		int current = budget;
		if (current <= 0) {
			return true;
		}
		if (used >= current) {
			deferredTotal++;
			return false;
		}
		used++;
		return true;
	}

	public int budget() {
		return budget;
	}

	public int used() {
		return used;
	}

	public long deferredTotal() {
		return deferredTotal;
	}
}
