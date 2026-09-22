package com.endiq.beryllium.tick;

import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One shared "are Beryllium's off-thread safety hooks really installed?" check.
 *
 * <p>Both experimental features (async random ticks and parallel entity ticking) depend
 * on two hooks that cannot be trusted to exist on every Minecraft version:
 * <ol>
 *   <li>the {@code Level#setBlock} hook that records world mutations instead of
 *       performing them ({@link DeferredWorldActions}), and</li>
 *   <li>the {@code Level#random} field redirect that gives a worker its own random
 *       source.</li>
 * </ol>
 * If either is missing, the "optimisation" would mutate the world from several threads at
 * once. Rather than hoping, Beryllium performs a <em>calibration pass</em>: it runs the
 * vanilla body once on the server thread with deferral active, performs one deliberately
 * harmless mutation (writing a block to the state it already has, with no update flags —
 * a no-op however it is handled), and checks whether the hook recorded it. Only if both
 * hooks are observed working does Beryllium allow a single concurrent task.
 *
 * <p>This is why the experimental features can be on by default without gambling with the
 * world: an unhooked version is detected before any parallelism happens, and the feature
 * turns itself off with a clear log line.
 */
public final class ExperimentalCertification {
	private static boolean attempted = false;
	private static boolean certified = false;
	private static volatile long randomRedirects = 0;
	private static volatile long certifications = 0;

	private ExperimentalCertification() {
	}

	public static boolean isCertified() {
		return certified && DeferredWorldActions.isExperimentalEnabled();
	}

	public static boolean wasAttempted() {
		return attempted;
	}

	/**
	 * Runs the vanilla body once on the calling thread and certifies the hooks, unless
	 * calibration has already been attempted once this session.
	 *
	 * @param level    the level the body belongs to (supplies the probe position)
	 * @param body     the vanilla work to run while deferral is active
	 * @param probePos a loaded position to use for the harmless probe mutation
	 */
	public static void calibrate(Level level, BlockPos probePos, Runnable body) {
		if (attempted) {
			return;
		}
		attempted = true;

		long deferredBefore = DeferredWorldActions.deferredTotal();
		long redirectsBefore = randomRedirects;
		boolean probeRan = false;

		DeferredWorldActions.begin();
		try {
			probeRan = probe(level, probePos);
			if (body != null) {
				// Held so the injector that hands us the vanilla body does not try to take
				// this very call over again.
				ReplayGuard.run(body);
			}
		} catch (Throwable t) {
			DeferredWorldActions.noteFailure(t);
			return;
		} finally {
			// We are on the server thread, so replaying here is plain vanilla behaviour
			// with (at worst) one redundant no-op block write.
			DeferredWorldActions.replay(DeferredWorldActions.end());
		}

		boolean deferralLive = probeRan && DeferredWorldActions.deferredTotal() > deferredBefore;
		boolean rngRedirectLive = randomRedirects > redirectsBefore;
		if (deferralLive && rngRedirectLive) {
			certified = true;
			certifications++;
			BerylliumLog.info("[BERYLLIUM-TICK] Experimental off-thread ticking certified on this "
				+ "version (deferred world mutations and per-thread RNG both confirmed live).");
		} else {
			DeferredWorldActions.disableExperimental("the off-thread ticking hooks did not fire on "
				+ "this Minecraft version (deferralProbe=" + deferralLive
				+ ", rngRedirect=" + rngRedirectLive + ")");
		}
	}

	private static boolean probe(Level level, BlockPos probePos) {
		if (level == null || probePos == null) {
			return false;
		}
		try {
			BlockState state = level.getBlockState(probePos);
			level.setBlock(probePos, state, 0);
			return true;
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM-TICK] Certification probe skipped: " + t);
			return false;
		}
	}

	/** Called by the {@code Level#random} field redirect; also the redirect's proof of life. */
	public static Object threadRandom() {
		randomRedirects++;
		return WorkerRandom.get();
	}

	public static long randomRedirects() {
		return randomRedirects;
	}

	public static long certificationCount() {
		return certifications;
	}

	/** One lazily created {@code RandomSource} / {@code Random} per worker thread — reflection for 1.17. */
	private static final class WorkerRandom {
		private static final ThreadLocal<Object> LOCAL = ThreadLocal.withInitial(() -> {
			try {
				Class<?> c = Class.forName("net.minecraft.util.RandomSource");
				return c.getMethod("create").invoke(null);
			} catch (Throwable t) {
				return new java.util.Random();
			}
		});

		private WorkerRandom() {
		}

		static Object get() {
			return LOCAL.get();
		}
	}
}
