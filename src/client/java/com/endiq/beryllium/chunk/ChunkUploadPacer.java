package com.endiq.beryllium.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.performance.FrameBudget;
import com.endiq.beryllium.performance.FrameHealth;

import java.util.Queue;

/**
 * Chunk upload pacing.
 *
 * <p>When a chunk section finishes compiling, its geometry has to be uploaded to the GPU.
 * Vanilla drains that queue whenever it likes, and it likes to drain it all at once: after
 * a fast fly, a big redstone flood or a world-load burst, one frame can be handed dozens of
 * uploads. The upload itself is a driver call, and a burst of them is a textbook frame
 * spike — visible as a hitch exactly when the player is moving.
 *
 * <p>Vanilla's drain is a {@code while ((task = toUpload.poll()) != null) task.run();} loop.
 * That shape is what makes real pacing possible: answering {@code null} from the poll ends
 * the loop with everything still queued, so the remaining uploads happen on a later frame.
 * Nothing is dropped, nothing is reordered, and the queue cannot grow without bound because
 * the allowance below always clears a slice of the backlog.
 *
 * <p>Two terms decide how many uploads a frame may take:
 * <ul>
 *   <li><strong>{@code chunkUploadsPerFrame}</strong> — the steady-state allowance, halved
 *       on a frame that overran this device's normal cost and reduced to one on a spike.</li>
 *   <li><strong>a catch-up term</strong> — a fixed fraction of whatever is currently queued,
 *       so a large backlog always shrinks geometrically instead of trickling at the
 *       steady-state rate. This is what keeps a world load or a teleport from taking seconds
 *       to finish appearing, while still spreading the work over many frames.</li>
 * </ul>
 *
 * <p>The allowance is the larger of the two, so pacing only ever bites when the backlog is
 * small enough that spreading it out costs the player nothing.
 *
 * <p>One drain is never paced: the dispatcher's own {@code dispose()} flush, where each
 * remaining upload is also what returns its buffer pack to the pool. See
 * {@link #beginUnpacedFlush()}.
 */
public final class ChunkUploadPacer {
	/**
	 * A frame clears at least {@code 1/divisor} of the backlog. The divisor widens as frames
	 * get more expensive, so a struggling device spreads the same backlog over more frames
	 * rather than adding to the spike it is already having.
	 */
	private static final int CATCH_UP_DIVISOR_HEALTHY = 8;
	private static final int CATCH_UP_DIVISOR_OVERRUNNING = 16;
	private static final int CATCH_UP_DIVISOR_IN_TROUBLE = 32;

	private static final ChunkUploadPacer INSTANCE = new ChunkUploadPacer();

	private boolean pacing;
	private int allowance;
	private int uploadsUsed;
	private int unpacedFlushDepth;
	private FrameHealth frameHealth = FrameHealth.HEALTHY;

	private long lastPollNanos;
	private long lastUploadNanos = -1L;
	private long uploadedTotal;
	private long deferredTotal;

	private ChunkUploadPacer() {
	}

	public static ChunkUploadPacer instance() {
		return INSTANCE;
	}

	/**
	 * @param lastFrameNanos    duration of the frame that just finished, or a non-positive
	 *                          value if it has not been measured
	 * @param averageFrameNanos this device's rolling average frame time, or a non-positive
	 *                          value if it has not been measured yet
	 */
	public void onFrameStart(long lastFrameNanos, long averageFrameNanos) {
		BerylliumConfig config = Beryllium.config();
		int configured = config == null ? 2 : Math.max(0, config.chunkUploadsPerFrame);
		frameHealth = FrameBudget.healthOf(lastFrameNanos, averageFrameNanos);

		if (configured == 0 || config == null || !config.enabled || !config.chunkUploadPacing
			|| Beryllium.isChunkOptimizationDeferredToOtherMod()) {
			// 0 (or the feature off) means "no pacing" — let vanilla drain as it pleases.
			pacing = false;
			allowance = 0;
		} else {
			pacing = true;
			allowance = switch (frameHealth) {
				case IN_TROUBLE -> 1;
				case OVERRUNNING -> Math.max(1, configured / 2);
				case HEALTHY -> configured;
			};
		}
		uploadsUsed = 0;
		lastPollNanos = 0L;
		// Self-healing: a teardown flush that threw would otherwise leave pacing off for the
		// rest of the session. The flush is synchronous inside dispose(), so it is always
		// finished again before the next frame starts.
		unpacedFlushDepth = 0;
	}

	/**
	 * Marks the start of a drain that must not be paced.
	 *
	 * <p>From 1.20.6 the dispatcher's own {@code dispose()} calls
	 * {@code uploadAllPendingUploads()} as its teardown flush: every upload still queued has
	 * to run, because each one is also what hands its buffer pack back to the pool. Pacing
	 * that call would strand them at world exit, which is a GPU resource leak — the one
	 * thing a deferral must never turn into.
	 */
	public void beginUnpacedFlush() {
		unpacedFlushDepth++;
	}

	/** Ends a {@link #beginUnpacedFlush()} scope. */
	public void endUnpacedFlush() {
		if (unpacedFlushDepth > 0) {
			unpacedFlushDepth--;
		}
	}

	/**
	 * Stands in for the {@code Queue.poll()} inside vanilla's upload drain.
	 *
	 * <p>Returning {@code null} ends vanilla's loop. The entry that would have been polled
	 * stays in the queue and is uploaded on a later frame — which is safe precisely because
	 * it is a queue.
	 *
	 * @param queue the queue vanilla is polling
	 * @return the next upload, or {@code null} to stop the drain here
	 */
	public Object nextUpload(Queue<?> queue) {
		if (queue == null) {
			return null;
		}
		if (!pacing || unpacedFlushDepth > 0) {
			return timedPoll(queue);
		}
		Object head = safePeek(queue);
		if (!(head instanceof Runnable)) {
			/*
			 * Not the upload queue. From 1.21.8 the same method also drains a queue of
			 * SectionMesh objects that have to be *closed*, and every entry in the upload
			 * queue is a Runnable while none of those are. Passing that queue straight
			 * through matters: an upload pacer that also stopped mesh cleanup would leak GPU
			 * resources for as long as it deferred.
			 */
			return queue.poll();
		}

		int allowed = allowanceFor(safeSize(queue));
		if (uploadsUsed >= allowed) {
			deferredTotal++;
			return null;
		}
		uploadsUsed++;
		return timedPoll(queue);
	}

	/**
	 * The steady-state allowance, raised by a slice of the current backlog so a big queue
	 * always shrinks geometrically instead of waiting its way through at two per frame.
	 */
	private int allowanceFor(int backlog) {
		if (backlog <= 0) {
			return allowance;
		}
		int divisor = switch (frameHealth) {
			case IN_TROUBLE -> CATCH_UP_DIVISOR_IN_TROUBLE;
			case OVERRUNNING -> CATCH_UP_DIVISOR_OVERRUNNING;
			case HEALTHY -> CATCH_UP_DIVISOR_HEALTHY;
		};
		int catchUp = Math.max(1, (backlog + divisor - 1) / divisor);
		return Math.max(allowance, catchUp);
	}

	/**
	 * Drops every measurement. Called when the level changes: the upload backlog, and what an
	 * upload costs, both belong to the world that just went away.
	 */
	public void reset() {
		uploadsUsed = 0;
		lastPollNanos = 0L;
		lastUploadNanos = -1L;
		unpacedFlushDepth = 0;
	}

	private Object timedPoll(Queue<?> queue) {
		long now = System.nanoTime();
		if (lastPollNanos != 0L) {
			// The gap between two polls is the cost of running the upload in between.
			lastUploadNanos = now - lastPollNanos;
		}
		lastPollNanos = now;
		Object polled = queue.poll();
		if (polled != null) {
			uploadedTotal++;
		}
		return polled;
	}

	private static Object safePeek(Queue<?> queue) {
		try {
			return queue.peek();
		} catch (Throwable t) {
			return null;
		}
	}

	private static int safeSize(Queue<?> queue) {
		try {
			return queue.size();
		} catch (Throwable t) {
			return 0;
		}
	}

	/** Uploads allowed this frame before the catch-up term is applied; 0 when pacing is off. */
	public int budget() {
		return allowance;
	}

	public int used() {
		return uploadsUsed;
	}

	/** Nanoseconds the most recent single upload took, or {@code -1} before the second one. */
	public long lastUploadNanos() {
		return lastUploadNanos;
	}

	public long uploadedTotal() {
		return uploadedTotal;
	}

	/** How many times a drain was stopped early; the entries stayed queued and were not lost. */
	public long deferredTotal() {
		return deferredTotal;
	}
}
