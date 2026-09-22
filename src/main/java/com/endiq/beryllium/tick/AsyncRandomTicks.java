package com.endiq.beryllium.tick;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Async random ticks — runs vanilla's per-chunk random-tick pass on worker threads.
 *
 * <p>Random ticks drive crop growth, saplings, grass, fire, ice, leaf decay and copper
 * aging. Vanilla performs them one chunk at a time on the server thread, and with a
 * raised {@code randomTickSpeed} — or simply a large farm — that loop becomes a real line
 * item in the tick profile. It is also naturally chunk-shaped, which makes it the one
 * part of the world tick that can be moved off the main thread without redesigning
 * Minecraft's concurrency model.
 *
 * <h3>What makes this safe</h3>
 * <ol>
 *   <li><strong>Read-only snapshot.</strong> Every world mutation a worker attempts is
 *       recorded by {@link DeferredWorldActions} instead of performed, then replayed by
 *       the main thread once the batch joins. Two workers can never observe a
 *       half-applied block change — the usual way off-thread world work corrupts a
 *       save.</li>
 *   <li><strong>Per-thread RNG.</strong> Vanilla draws random positions from one shared
 *       {@code Level#random}. Two threads tearing at one {@code RandomSource} can produce
 *       out-of-range values (in the worst case, a position in an unloaded chunk), so
 *       while a worker is inside {@code tickChunk} reads of that field are redirected to
 *       a random source owned by the thread.</li>
 *   <li><strong>Calibration before concurrency.</strong> The first eligible chunk runs
 *       <em>on the main thread</em> with deferral active, and Beryllium verifies that
 *       both the deferral hook and the RNG redirect actually fired. If either is missing
 *       on this Minecraft version the feature disables itself for the session. It never
 *       guesses, and it never runs a single concurrent task un-certified.</li>
 *   <li><strong>Weather opt-out.</strong> Chunks in a raining or thundering world are
 *       left to vanilla, because that path can strike lightning and spawn entities
 *       rather than merely ticking blocks.</li>
 *   <li><strong>Self-disabling.</strong> Any throw inside a worker permanently turns the
 *       feature off for the session and logs why.</li>
 * </ol>
 *
 * <p>Behavioural difference from vanilla, stated plainly: mutations made during a batch
 * become visible to the rest of the game only after that batch completes, so the exact
 * order in which two distant chunks grow or burn can differ from a strictly sequential
 * tick. Nothing is lost or duplicated.
 */
public final class AsyncRandomTicks {
	private static final int MAX_PENDING_TASKS = 64;

	private static final List<Future<List<Runnable>>> pending = new ArrayList<>();
	private static boolean batchOpen = false;
	private static long batchGameTime = Long.MIN_VALUE;
	private static long chunksRunAsync = 0;
	private static long chunksRunInline = 0;

	private AsyncRandomTicks() {
	}

	/** Opens a batch. Called at the head of {@code ServerChunkCache#tick}. */
	public static void beginBatch() {
		if (batchOpen) {
			joinBatch();
		}
		pending.clear();
		batchOpen = true;
	}

	/**
	 * Waits for every dispatched chunk and replays their deferred mutations on the main
	 * thread. Called when {@code ServerChunkCache#tick} returns.
	 */
	public static void joinBatch() {
		if (!batchOpen) {
			return;
		}
		batchOpen = false;
		List<Runnable> all = new ArrayList<>();
		try {
			for (Future<List<Runnable>> future : pending) {
				List<Runnable> recorded = future.get();
				if (recorded != null && !recorded.isEmpty()) {
					all.addAll(recorded);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			DeferredWorldActions.noteFailure(e);
		} catch (Throwable t) {
			DeferredWorldActions.noteFailure(t);
		} finally {
			pending.clear();
		}
		DeferredWorldActions.replay(all);
	}

	/**
	 * Whether Beryllium should even try to take this chunk's random ticks off-thread this
	 * tick. Cheap and side-effect free: it is asked before the calibration pass runs.
	 */
	public static boolean shouldAttempt(ServerLevel level) {
		return level != null
			&& BerylliumConfigCache.asyncRandomTicks()
			&& DeferredWorldActions.isExperimentalEnabled();
	}

	/**
	 * Flushes the previous tick's batch if the game time has moved on, then opens a fresh
	 * one. This is what makes the feature independent of the enclosing
	 * {@code ServerChunkCache#tick} hooks: even on a version where those do not match,
	 * every recorded mutation is applied before the next tick's work starts.
	 */
	private static void maybeRotate(ServerLevel level) {
		long now = level == null ? 0L : level.getGameTime();
		if (batchOpen && now != batchGameTime) {
			joinBatch();
		}
		if (!batchOpen) {
			beginBatch();
			batchGameTime = now;
		}
	}

	public static boolean isEligible(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
		if (!batchOpen || !certified() || !DeferredWorldActions.isExperimentalEnabled()) {
			return false;
		}
		if (level == null || chunk == null || randomTickSpeed <= 0) {
			return false;
		}
		if (level.isRaining() || level.isThundering()) {
			// Lightning (and the snow/ice path) reaches outside pure block ticking.
			return false;
		}
		int cx = VanillaBridges.chunkX(chunk);
		int cz = VanillaBridges.chunkZ(chunk);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!level.hasChunk(cx + dx, cz + dz)) {
					return false;
				}
			}
		}
		return pending.size() < MAX_PENDING_TASKS;
	}

	/**
	 * Hands one chunk's random ticks to a worker, or runs it inline when the feature is
	 * inactive (including during the main-thread calibration pass).
	 *
	 * @return true if the work was dispatched (the caller must not run it again)
	 */
	public static boolean dispatch(ServerLevel level, LevelChunk chunk, int randomTickSpeed, Runnable run) {
		if (!shouldAttempt(level)) {
			chunksRunInline++;
			return false;
		}
		maybeRotate(level);
		if (!isEligible(level, chunk, randomTickSpeed)) {
			chunksRunInline++;
			return false;
		}
		try {
			Callable<List<Runnable>> task = () -> {
				DeferredWorldActions.begin();
				try {
					ReplayGuard.run(run);
				} finally {
					List<Runnable> recorded = DeferredWorldActions.end();
					DeferredWorldActions.noteTask(!recorded.isEmpty());
					return recorded;
				}
			};
			pending.add(BerylliumWorkers.pool(BerylliumConfigCache.asyncRandomTickThreads()).submit(task));
			chunksRunAsync++;
			return true;
		} catch (Throwable t) {
			DeferredWorldActions.noteFailure(t);
			chunksRunInline++;
			return false;
		}
	}

	/**
	 * Runs the vanilla body once on the calling thread with deferral active, then checks
	 * that Beryllium's two safety hooks are genuinely live on this Minecraft version.
	 * The shared {@link ExperimentalCertification} performs — and remembers — that check,
	 * so it happens exactly once per session no matter which feature asks first.
	 */
	public static void calibrate(Level level, LevelChunk chunk, Runnable run) {
		ExperimentalCertification.calibrate(level, chunk == null ? null : chunk.getPos().getWorldPosition(), run);
	}

	public static boolean certified() {
		return ExperimentalCertification.isCertified();
	}

	/** Called by the {@code Level#random} field redirect inside {@code tickChunk}. */
	public static Object threadRandom() {
		return ExperimentalCertification.threadRandom();
	}

	public static long randomRedirects() {
		return ExperimentalCertification.randomRedirects();
	}

	public static long chunksRunAsync() {
		return chunksRunAsync;
	}

	public static long chunksRunInline() {
		return chunksRunInline;
	}
}
