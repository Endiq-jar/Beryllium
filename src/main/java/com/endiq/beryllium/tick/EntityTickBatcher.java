package com.endiq.beryllium.tick;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Parallel entity processing: spreads one tick's entity work over the worker pool.
 *
 * <p>Vanilla ticks every entity on the server thread, one after another. Most entities
 * never touch each other, so most of that serialization is unnecessary — but "just run
 * them on threads" is how worlds get corrupted: {@code Entity#tick} pushes neighbouring
 * entities, writes blocks, spawns drops and fires events.
 *
 * <p>Beryllium's approach:
 * <ol>
 *   <li><strong>Collect, don't intercept.</strong> During {@code Level#tickEntities} the
 *       eligible entities are taken out of the loop (vanilla's own call for them is
 *       skipped) and their vanilla tick body is retained.</li>
 *   <li><strong>Colour the chunks.</strong> Entities are grouped by
 *       {@code (chunkX mod 3, chunkZ mod 3)}. Two chunks in the same colour are at least
 *       three chunks apart on both axes, so their 3x3 neighbourhoods are disjoint — no
 *       entity in one group can push, collide with or read an entity being ticked in
 *       another group in the same round.</li>
 *   <li><strong>Record mutations.</strong> Anything a worker tries to change about the
 *       world is deferred and replayed by the server thread after the round joins, so all
 *       workers see one consistent snapshot.</li>
 *   <li><strong>Run the nine rounds in order.</strong> Within a round the groups run in
 *       parallel; between rounds everything is joined, so the next round sees the
 *       previous round's mutations.</li>
 * </ol>
 *
 * <p>Excluded by construction: players (they own packet and inventory state), anything
 * riding or ridden (vanilla ticks passengers inside the vehicle's tick), anything already
 * removed, and anything whose 3x3 chunk neighbourhood is not fully loaded (an entity tick
 * must never trigger a chunk load from a worker). Below
 * {@code parallelEntityTickMinEntities} the batch simply runs sequentially — parallelism
 * that small costs more than it saves.
 */
public final class EntityTickBatcher {
	/** 3x3 colouring: nine rounds, each internally parallel, each pairwise disjoint. */
	private static final int COLOURS = 9;

	private static final EntityTickBatcher INSTANCE = new EntityTickBatcher();

	private final List<Job> collected = new ArrayList<>(512);
	private boolean collecting = false;
	private long entitiesTickedInParallel = 0;
	private long roundsRun = 0;
	private long deferredMutations = 0;

	private EntityTickBatcher() {
	}

	public static EntityTickBatcher instance() {
		return INSTANCE;
	}

	private static final class Job {
		final Runnable tick;
		final int colour;

		Job(Runnable tick, int colour) {
			this.tick = tick;
			this.colour = colour;
		}
	}

	/** Opens collection at the head of {@code Level#tickEntities}. */
	public void beginEntities() {
		collected.clear();
		collecting = true;
	}

	public boolean isCollecting() {
		return collecting;
	}

	/**
	 * Runs one entity's vanilla tick inline, with deferral active, so the shared safety
	 * hooks can be verified before any entity is ticked off-thread. Used once per session,
	 * and only by whichever experimental feature asks first.
	 */
	public void calibrate(Level level, Entity entity, Runnable body) {
		ExperimentalCertification.calibrate(level, entity == null ? null : entity.blockPosition(), body);
	}

	/**
	 * @return true when the injector on {@code Level#tickNonPassenger} should hand the
	 *         entity over to the batch instead of letting vanilla tick it inline.
	 */
	public boolean shouldIntercept() {
		return collecting
			&& !ReplayGuard.isReplaying()
			&& BerylliumConfigCache.parallelEntityTicking()
			&& ExperimentalCertification.isCertified()
			&& DeferredWorldActions.isExperimentalEnabled();
	}

	/**
	 * Offers an entity's vanilla tick to the batch.
	 *
	 * @return true if Beryllium took responsibility for ticking it (the caller must skip
	 *         vanilla's own call); false if it must be ticked inline, now.
	 */
	public boolean collect(Entity entity, Runnable tick) {
		if (!collecting || entity == null || tick == null || !isEligible(entity)) {
			return false;
		}
		Level level = VanillaBridges.levelOf(entity);
		int cx = ((int) Math.floor(entity.getX())) >> 4;
		int cz = ((int) Math.floor(entity.getZ())) >> 4;
		int colour = Math.floorMod(cx, 3) * 3 + Math.floorMod(cz, 3);
		collected.add(new Job(tick, colour));
		return true;
	}

	/** Runs everything collected this tick. Called when {@code Level#tickEntities} returns. */
	public void finishEntities() {
		if (!collecting) {
			return;
		}
		collecting = false;

		int size = collected.size();
		if (size == 0) {
			return;
		}

		int minEntities = BerylliumConfigCache.parallelEntityTickMinEntities();
		if (size < minEntities || !ExperimentalCertification.isCertified()) {
			runSequential();
			collected.clear();
			return;
		}

		int chunkSize = BerylliumConfigCache.parallelEntityTickChunkSize();
		int threads = BerylliumConfigCache.parallelEntityTickThreads();
		try {
			for (int colour = 0; colour < COLOURS; colour++) {
				runColour(colour, chunkSize, threads);
			}
		} finally {
			collected.clear();
		}
	}

	private void runColour(int colour, int chunkSize, int threads) {
		List<List<Job>> groups = new ArrayList<>();
		List<Job> current = null;
		for (Job job : collected) {
			if (job.colour != colour) {
				continue;
			}
			if (current == null || current.size() >= chunkSize) {
				current = new ArrayList<>(chunkSize);
				groups.add(current);
			}
			current.add(job);
		}
		if (groups.isEmpty()) {
			return;
		}

		List<Future<List<Runnable>>> futures = new ArrayList<>(groups.size());
		for (List<Job> group : groups) {
			Callable<List<Runnable>> task = () -> {
				DeferredWorldActions.begin();
				try {
					for (Job job : group) {
						ReplayGuard.run(job.tick);
					}
					return DeferredWorldActions.end();
				} finally {
					// Ensure the thread-local scope is always cleaned up, even on throw.
					DeferredWorldActions.end();
				}
			};
			futures.add(BerylliumWorkers.pool(threads).submit(task));
		}

		List<Runnable> all = new ArrayList<>();
		try {
			for (Future<List<Runnable>> future : futures) {
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
		}

		deferredMutations += all.size();
		entitiesTickedInParallel += countJobs(groups);
		roundsRun++;
		DeferredWorldActions.replay(all);
	}

	private void runSequential() {
		for (Job job : collected) {
			try {
				ReplayGuard.run(job.tick);
			} catch (Throwable t) {
				DeferredWorldActions.noteFailure(t);
				return;
			}
		}
	}

	private static int countJobs(List<List<Job>> groups) {
		int total = 0;
		for (List<Job> group : groups) {
			total += group.size();
		}
		return total;
	}

	/**
	 * Eligibility is deliberately narrow. Anything that can legitimately reach outside its
	 * own chunk neighbourhood, or that carries per-player state, stays on the server
	 * thread.
	 */
	private static boolean isEligible(Entity entity) {
		if (!BerylliumConfigCache.parallelEntityTicking()) {
			return false;
		}
		if (!ExperimentalCertification.isCertified()) {
			return false;
		}
		if (entity instanceof ServerPlayer || entity.isRemoved()) {
			return false;
		}
		// Vehicles tick their passengers themselves; splitting them changes ride physics
		// and creates cross-thread passenger mutation.
		if (entity.isVehicle() || entity.isPassenger()) {
			return false;
		}
		Level level = VanillaBridges.levelOf(entity);
		if (level == null || level.isClientSide()) {
			return false;
		}
		if (level.players().isEmpty()) {
			// No observer: this level is effectively idle, leave it to vanilla's cheap path.
			return false;
		}

		int cx = ((int) Math.floor(entity.getX())) >> 4;
		int cz = ((int) Math.floor(entity.getZ())) >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!level.hasChunk(cx + dx, cz + dz)) {
					return false;
				}
			}
		}
		return true;
	}

	public long entitiesTickedInParallel() {
		return entitiesTickedInParallel;
	}

	public long roundsRun() {
		return roundsRun;
	}

	public long deferredMutations() {
		return deferredMutations;
	}

	public String summary() {
		return "parallel entities=" + entitiesTickedInParallel + " rounds=" + roundsRun
			+ " deferred=" + deferredMutations + " workers="
			+ BerylliumWorkers.activeThreads() + " tasks=" + BerylliumWorkers.tasksRun();
	}
}
