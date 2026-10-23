package com.endiq.beryllium.culling.async;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.renderer.culling.Frustum;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous Culling Pipeline: the distance and frustum intersection math runs on a
 * background worker instead of blocking the frame that needs the answer.
 *
 * <h2>The shape of a frame</h2>
 *
 * <ol>
 *   <li>The render thread copies the live {@link Frustum} into a {@link FrustumPlanes} —
 *       plain doubles, validated against the frustum it came from.</li>
 *   <li>It publishes that snapshot with the current epoch and wakes the worker.</li>
 *   <li>The worker re-tests every box in the {@link CullCache} against the snapshot and
 *       republishes them under the new epoch. No Minecraft object is touched.</li>
 *   <li>Later in the same frame, when a mixin asks whether a block is visible, the answer
 *       is a map lookup. On a miss the render thread asks the live frustum directly and
 *       stores the result for next time.</li>
 * </ol>
 *
 * <p>So the render thread's worst case is exactly what it was before this class existed —
 * one synchronous frustum test per uncached box — and its best case is no frustum math at
 * all. There is no path where waiting for the worker is faster than doing the work.
 *
 * <h2>Why the worker never blocks the frame</h2>
 *
 * <p>Nothing here joins, waits on a future, or holds a lock the render thread needs. The
 * hand-off is a volatile snapshot reference and a bounded queue of depth one: if the worker
 * is still busy, the new frame's request is coalesced into the one already pending rather
 * than queued behind it, because a carry-forward for frame N supersedes the one for N-1.
 * A worker that falls behind makes the cache colder; it never makes a frame longer.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>The pipeline switches itself off and stays off when the frustum cannot be snapshotted
 * faithfully — that is, when {@link FrustumSnapshotter} repeatedly finds its copy
 * disagreeing with the original. A pipeline that cannot be trusted to reproduce the live
 * frustum must not be trusted with culling decisions, and the synchronous path it falls
 * back to is the same code that runs when the feature is disabled.
 */
public final class AsyncCullingPipeline {
	/**
	 * Consecutive validation failures that retire the pipeline. One failure can be a
	 * transient oddity (a captured debug frustum, a frame mid-resize); a run of them means
	 * the extraction genuinely does not describe this release's frustum.
	 */
	private static final int FAILURE_LIMIT = 3;

	private static final AsyncCullingPipeline INSTANCE = new AsyncCullingPipeline();

	private final CullCache cache = new CullCache();
	private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>(4);
	private final AtomicBoolean taskPending = new AtomicBoolean(false);
	private final AtomicLong epoch = new AtomicLong(0L);

	/** The snapshot the worker should carry the cache forward to. Written by the render
	 *  thread, read by the worker; volatile is sufficient because a task is only ever
	 *  enqueued after this is set, and the queue provides the happens-before edge. */
	private volatile FrustumPlanes publishedPlanes;
	private volatile long publishedEpoch = -1L;

	/** The frame counter {@link #beginFrame()} advanced to, before a frustum was published
	 *  for it. {@code -1} once published, so a second publish in the same frame is a no-op. */
	private volatile long pendingFrame = -1L;

	private volatile Thread worker;
	private volatile boolean running = false;
	private volatile boolean retired = false;

	private final AtomicLong framesPublished = new AtomicLong();
	private final AtomicLong boxesCarried = new AtomicLong();
	private final AtomicLong tasksCoalesced = new AtomicLong();
	private final AtomicLong validationFailures = new AtomicLong();

	/** A unit of worker-side culling work: carry the cache forward to one frustum. */
	private static final class Task {
		final FrustumPlanes planes;
		final long epoch;

		Task(FrustumPlanes planes, long epoch) {
			this.planes = planes;
			this.epoch = epoch;
		}
	}

	private AsyncCullingPipeline() {
	}

	public static AsyncCullingPipeline instance() {
		return INSTANCE;
	}

	/**
	 * Called once per frame from the render thread's frame hook, before any culling.
	 *
	 * <p>Only advances the frame counter. It deliberately does <em>not</em> snapshot the
	 * frustum here: at this point in {@code Minecraft#runTick} the frustum still holds the
	 * planes from the frame that just finished, so copying it now and labelling it with this
	 * frame's epoch would hand the worker a picture of where the camera <em>was</em>. Cached
	 * answers derived from that could cull geometry the player has just turned towards —
	 * the one failure this pipeline must never have.
	 *
	 * <p>The snapshot is taken later, by {@link #publish(Frustum)}, at the moment the first
	 * visibility question of the frame is asked. That is when the frustum is current.
	 */
	public void beginFrame() {
		if (retired || !enabled()) {
			return;
		}
		pendingFrame = epoch.get() + 1L;
	}

	/**
	 * Publishes the frustum the current frame is actually culling against, once per frame.
	 *
	 * <p>Called from the render thread the first time a visibility test needs a frustum this
	 * frame. Cheap enough to call on every test: after the first, it is a single volatile
	 * compare.
	 */
	public void publish(Frustum frustum) {
		if (retired || !enabled() || frustum == null) {
			return;
		}
		long frame = pendingFrame;
		if (frame < 0L) {
			// Already published for this frame.
			return;
		}
		try {
			FrustumPlanes planes = FrustumSnapshotter.snapshot(frustum);
			if (planes == null) {
				long failures = validationFailures.incrementAndGet();
				if (failures >= FAILURE_LIMIT) {
					retire("frustum snapshots could not be validated " + failures + " times");
				}
				return;
			}
			validationFailures.set(0L);

			// Claim the frame before publishing, so a second call cannot double-publish.
			if (pendingFrame != frame) {
				return;
			}
			pendingFrame = -1L;

			epoch.set(frame);
			publishedPlanes = planes;
			publishedEpoch = frame;
			framesPublished.incrementAndGet();

			startWorkerIfNeeded();
			submit(new Task(planes, frame));
		} catch (Throwable t) {
			// Never let the pipeline take the frame down with it.
			retire("unexpected failure: " + t);
		}
	}

	private void submit(Task task) {
		// Coalesce: one pending carry-forward is enough, because a newer task supersedes
		// an older one. Without this a slow worker would accumulate a queue of work that is
		// all obsolete by the time it runs.
		if (!taskPending.compareAndSet(false, true)) {
			tasksCoalesced.incrementAndGet();
			return;
		}
		if (!queue.offer(task)) {
			taskPending.set(false);
			tasksCoalesced.incrementAndGet();
		}
	}

	private void startWorkerIfNeeded() {
		if (running) {
			return;
		}
		synchronized (this) {
			if (running) {
				return;
			}
			running = true;
			Thread thread = new Thread(this::workerLoop, "Beryllium-Culling-Worker");
			// Daemon, and below the render thread in priority: this work is an optimisation,
			// and it must never be able to keep the JVM alive or to steal time from the frame.
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			worker = thread;
			thread.start();
		}
	}

	private void workerLoop() {
		try {
			while (running) {
				Task task = queue.poll(250L, java.util.concurrent.TimeUnit.MILLISECONDS);
				if (task == null) {
					// Idle. Keep waiting rather than exiting: a frame can start at any moment
					// and re-creating a thread per frame would cost more than the culling did.
					continue;
				}
				taskPending.set(false);
				int carried = cache.carryForward(task.planes, task.epoch);
				boxesCarried.addAndGet(carried);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			// A worker that throws must not leave the pipeline in a half-alive state.
			BerylliumLog.debug("[BERYLLIUM] culling worker stopped: " + t);
			retired = true;
			running = false;
		}
	}

	/**
	 * The cached answer for a block, or {@code null} when the render thread must work it out
	 * itself. Only ever returns an answer measured against the current frame's frustum.
	 */
	public Boolean cachedVisibility(long key, int bucket) {
		long frame = publishedEpoch;
		if (frame < 0L) {
			return null;
		}
		try {
			return cache.lookup(key, bucket, frame);
		} catch (Throwable t) {
			return null;
		}
	}

	/** The epoch a {@link #cachedVisibility} hit was measured against. */
	public long currentEpoch() {
		return publishedEpoch;
	}

	/**
	 * Remembers an answer the render thread computed synchronously, so a later worker pass
	 * can carry it forward instead of the render thread recomputing it every frame.
	 */
	public void record(long key, int bucket, boolean visible,
	                   double minX, double minY, double minZ,
	                   double maxX, double maxY, double maxZ) {
		if (retired || !enabled()) {
			return;
		}
		long frame = publishedEpoch;
		if (frame < 0L) {
			return;
		}
		try {
			cache.store(key, bucket, frame, visible, minX, minY, minZ, maxX, maxY, maxZ);
		} catch (Throwable ignored) {
			// An unrecorded answer costs a recomputation later, nothing more.
		}
	}

	/** Whether the feature is switched on. A missing config means "off": do not start
	 *  background threads before the config has loaded. */
	private static boolean enabled() {
		BerylliumConfig config = Beryllium.config();
		return config != null && config.enabled && config.visibilityCulling && config.asyncVisibilityCulling;
	}

	/**
	 * Stops the pipeline for good. Called when snapshots stop validating, and on client
	 * shutdown. Idempotent, and safe to call from any thread.
	 */
	private void retire(String reason) {
		if (retired) {
			return;
		}
		retired = true;
		BerylliumLog.debug("[BERYLLIUM] asynchronous culling retired: " + reason
			+ "; visibility culling continues synchronously");
		shutdown();
	}

	/**
	 * Drops everything remembered and stops the worker. Called on world change: cached
	 * answers belong to the world they were measured in.
	 */
	public void onWorldChanged() {
		cache.clear();
		publishedEpoch = -1L;
		publishedPlanes = null;
		pendingFrame = -1L;
		epoch.set(0L);
		FrustumSnapshotter.invalidate();
		validationFailures.set(0L);
	}

	/** Stops the worker thread. The pipeline can be restarted by a later frame unless it
	 *  has been {@linkplain #retire(String) retired}. */
	public void shutdown() {
		running = false;
		Thread thread = worker;
		if (thread != null) {
			thread.interrupt();
			worker = null;
		}
		queue.clear();
		taskPending.set(false);
	}

	public boolean isRetired() {
		return retired;
	}

	public boolean isRunning() {
		return running;
	}

	public long framesPublished() {
		return framesPublished.get();
	}

	/** Boxes re-tested on the worker, i.e. frustum tests the render thread did not do. */
	public long boxesCarried() {
		return boxesCarried.get();
	}

	public long tasksCoalesced() {
		return tasksCoalesced.get();
	}

	public CullCache cache() {
		return cache;
	}
}
