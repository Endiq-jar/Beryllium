package com.endiq.beryllium.performance;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.profiler.FrameProfiler;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 — gives {@link FrameBudgetScheduler} a live work source.
 *
 * <p>Every rendered frame ({@code WorldRenderEvents.START}, the same clock
 * {@link FrameProfiler} uses) the registered recurring tasks are submitted to the
 * scheduler with their priorities, then the scheduler is given a millisecond budget and
 * runs tasks until it is spent. CRITICAL tasks always run in full (that is
 * {@link FrameBudgetScheduler}'s contract); everything else yields at the budget.
 *
 * <p>The budget is derived from live frame-time statistics rather than a fixed value:
 * {@code min(frameBudgetMillisPerFrame, ~10% of the current frame time, capped at the
 * 60 FPS budget)}. During a frame-time spike the budget shrinks and protects the frame;
 * on a fast machine the maintenance work gets more room. This keeps deferred work
 * strictly off the frame's critical path.
 *
 * <p>Current live work sources (registered from {@code BerylliumClient}):
 * <ul>
 *   <li>{@code LOW} — chunk rebuild queue telemetry ({@code ChunkRebuildManager}), rate-limited.</li>
 *   <li>{@code BACKGROUND} — shader preloader background scan / cache sweep ({@code ShaderPreloader}).</li>
 * </ul>
 * The {@link #submit} API is public so future subsystems (deferred buffer disposal via
 * {@code DeferredReleaseQueue}, cache pruning, ...) can drop one-shot work in here too.
 */
public final class FrameMaintenanceScheduler {
	private static volatile FrameMaintenanceScheduler instance;

	private final FrameBudgetScheduler scheduler = new FrameBudgetScheduler();
	private final List<Runnable> recurringTasks = new ArrayList<>();
	private final FrameProfiler profiler;

	public FrameMaintenanceScheduler(FrameProfiler profiler) {
		this.profiler = profiler;
	}

	public static FrameMaintenanceScheduler instance() {
		return instance;
	}

	public static void setInstance(FrameMaintenanceScheduler scheduler) {
		instance = scheduler;
	}

	public void register() {
		WorldRenderEvents.START.register(context -> onFrameStart());
	}

	/** Registers a task that is (re)submitted every rendered frame and runs when the
	 *  frame budget allows. Tasks must be cheap and must return promptly — they are
	 *  executed on the render thread. */
	public void addRecurringTask(WorkPriority priority, Runnable task) {
		if (priority == null || task == null) {
			throw new IllegalArgumentException("priority and task must not be null");
		}
		recurringTasks.add(() -> scheduler.submit(priority, task));
	}

	/** One-shot work: runs on the next frame whose budget reaches its priority class. */
	public void submit(WorkPriority priority, Runnable task) {
		scheduler.submit(priority, task);
	}

	public int pendingTotal() {
		return scheduler.totalPending();
	}

	private void onFrameStart() {
		for (Runnable recurring : recurringTasks) {
			recurring.run();
		}
		long budgetNanos = computeBudgetNanos();
		if (budgetNanos > 0) {
			scheduler.runFor(budgetNanos);
		}
	}

	private long computeBudgetNanos() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.frameBudgetScheduling) {
			return 0;
		}

		long lastFrameNanos = profiler.lastFrameNanos();
		long targetNanos = FrameBudget.targetFrameNanos(60);
		long currentFrameBudget = lastFrameNanos > 0 ? Math.min(lastFrameNanos, targetNanos) : targetNanos;
		// ~10% of the frame, but at least 0.25 ms so tiny tasks can still complete.
		long autoBudget = Math.max(250_000L, currentFrameBudget / 10);
		long capNanos = (long) (Math.max(0.0, config.frameBudgetMillisPerFrame) * 1_000_000.0);
		return Math.min(capNanos, autoBudget);
	}
}
