package com.endiq.beryllium.performance;

/**
 * Priority levels for work submitted to {@link FrameBudgetScheduler}. Ordinal order is
 * priority order — CRITICAL always runs, everything else yields once the frame's time
 * budget runs out.
 */
public enum WorkPriority {
	CRITICAL,
	HIGH,
	NORMAL,
	LOW,
	BACKGROUND
}
