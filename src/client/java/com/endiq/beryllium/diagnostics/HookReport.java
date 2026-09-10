package com.endiq.beryllium.diagnostics;

import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.shapes.CubePointRange;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 13 — runtime hook self-diagnosis.
 *
 * <p>Beryllium's mixins are declared with {@code require = 0} (and
 * {@code defaultRequire: 0} in the mixin configs), which is the correct
 * launch-safety choice: a signature that drifted in a Minecraft build makes that
 * hook a silent no-op instead of a crash. The cost of that choice is that a user
 * cannot tell the difference between "Beryllium is optimizing" and "every hook
 * silently failed to apply" — and on a device reporting unexpectedly low FPS, that
 * ambiguity is the first thing that must be eliminated.
 *
 * <p>This class removes it. Mixin merges each injector handler into its target class
 * as a private method with its original name, so a merged {@code beryllium$...}
 * method is strong evidence that the hook applied. Each probe below looks for its
 * handler on the exact target class; the report is logged once at client start
 * (after mixins have been applied) and is also available to the debug overlay.
 *
 * <p>Two honest caveats, stated here so the report is never over-trusted:
 * <ul>
 *   <li>Presence of the merged handler proves the mixin was <em>applied</em>; it
 *       proves the <em>injection point matched</em> only as far as Mixin's own
 *       resolution went. A hook that matched nothing can still leave its handler
 *       merged (with {@code require = 0}). The live counters in the debug overlay
 *       (fog-culled entities, occluded entities, text-shadow suppression) are the
 *       proof that a hook is actually <em>firing</em>.</li>
 *   <li>A NOT-APPLIED result cannot be fixed at runtime: it means this exact
 *       Minecraft build's internals differ from the profile's targets. The module
 *       listed in the report is the one to re-verify against a decompile of the
 *       running version.</li>
 * </ul>
 *
 * <p>Everything in here is reflection over classes that are already loaded by the
 * client (the probe targets are the same classes the mixins patch), wrapped so it
 * cannot throw. It never touches game state.
 */
public final class HookReport {
	private HookReport() {
	}

	/** Sentinel probe kind: look for the mixin's merged {@code @Unique} marker field,
	 *  used by the {@code @Overwrite}-style mixins that leave no handler method. */
	private static final String MARKER = "@marker";

	/** One probe: a human-readable feature name, the target class, and the handler
	 *  method name (or {@link #MARKER}). */
	private record Probe(String feature, Class<?> target, String handler) {
	}

	private static volatile List<String> lastReport;

	public static String summaryLine() {
		List<String> report = lastReport;
		if (report == null) {
			return "Hooks: <not checked>";
		}
		int applied = 0;
		for (String line : report) {
			if (line.startsWith("+")) {
				applied++;
			}
		}
		return "Hooks: " + applied + "/" + report.size() + " applied";
	}

	/** Number of probes whose handler was found on the target class. */
	public static int appliedCount() {
		List<String> report = lastReport;
		if (report == null) {
			return -1;
		}
		int applied = 0;
		for (String line : report) {
			if (line.startsWith("+")) {
				applied++;
			}
		}
		return applied;
	}

	public static int probeCount() {
		List<String> report = lastReport;
		return report == null ? -1 : report.size();
	}

	/**
	 * Runs every probe, logs the table, and returns the raw result lines
	 * ({@code "+feat..."} / {@code "-feat..."}) for the overlay.
	 */
	public static List<String> runAndLog() {
		List<Probe> probes = probes();
		List<String> lines = new ArrayList<>(probes.size());
		int applied = 0;

		BerylliumLog.info("[BERYLLIUM-HOOKS] mixin application report for this client:");
		for (Probe probe : probes) {
			boolean found = MARKER.equals(probe.handler())
				? hasBerylliumMember(probe.target())
				: hasHandler(probe.target(), probe.handler());
			if (found) {
				applied++;
			}
			lines.add((found ? "+" : "-") + probe.feature());
			BerylliumLog.info("  " + (found ? "[applied]  " : "[MISSING]  ") + probe.feature());
		}

		lastReport = lines;

		if (applied < probes.size()) {
			BerylliumLog.warn("Beryllium has " + (probes.size() - applied) + " of " + probes.size()
				+ " hooks missing on this Minecraft build. Those features are inactive (they degrade to"
				+ " vanilla rather than crashing). If FPS is unexpectedly low, the missing entries above"
				+ " are the reason Beryllium looks inert here — they need re-verification against this"
				+ " exact version's mappings. The 1.21.4 artifact is the fully verified renderer profile;"
				+ " other versions ship the compatibility core, which intentionally contains no renderer"
				+ " hooks at all.");
		} else {
			BerylliumLog.info("[BERYLLIUM-HOOKS] all " + probes.size()
				+ " hooks applied; culling and text/leaves/pipeline features are active.");
		}
		return lines;
	}

	private static List<Probe> probes() {
		List<Probe> probes = new ArrayList<>();

		// Client renderer hooks (beryllium.client.mixins.json) — all @Inject handlers.
		probes.add(new Probe("entity culling (behind-camera + fog-wall + occlusion)",
			EntityRenderDispatcher.class, "beryllium$cullEntitiesBehindCamera"));
		probes.add(new Probe("block entity frustum/fog culling",
			BlockEntityRenderDispatcher.class, "beryllium$cullInvisibleBlockEntities"));
		probes.add(new Probe("name tag distance/fog culling",
			EntityRenderer.class, "beryllium$cullNameTag"));
		probes.add(new Probe("text shadow suppression",
			Font.class, "beryllium$suppress"));
		probes.add(new Probe("leaves internal-face culling",
			LeavesBlock.class, "beryllium$cullInternalLeavesFaces"));
		probes.add(new Probe("chunk rebuild prioritization (ViewArea)",
			ViewArea.class, "beryllium$queuePrioritizedSectionDirty"));
		probes.add(new Probe("chunk rebuild prioritization (LevelRenderer)",
			LevelRenderer.class, "beryllium$queuePrioritizedSectionDirty"));

		// Common voxel-shape suite (beryllium.mixins.json), gated at class-load time by
		// voxelShapeOptimizations in beryllium.json. Mixed probe styles: the suite has
		// both @Inject handlers (name probes) and @Overwrite replacements (the merged
		// @Unique marker field is the only reliable evidence — see the mixins).
		probes.add(new Probe("voxel shape specialization (Shapes.create)",
			Shapes.class, MARKER));
		probes.add(new Probe("voxel shape coordinate ranges (VoxelShape)",
			VoxelShape.class, MARKER));
		probes.add(new Probe("voxel shape merge fast path (Shapes.join)",
			Shapes.class, "beryllium$injectCustomListPair"));
		probes.add(new Probe("voxel shape match fast path (matchesAnywhere)",
			Shapes.class, "beryllium$cuboidMatchesAnywhere"));
		probes.add(new Probe("cached isShapeFullBlock (Block)",
			Block.class, MARKER));
		probes.add(new Probe("bit-aligned cuboid fast path (CubeVoxelShape)",
			CubeVoxelShape.class, "beryllium$onConstructed"));
		probes.add(new Probe("precomputed point ranges (CubePointRange)",
			CubePointRange.class, MARKER));
		probes.add(new Probe("lazy entity collision context",
			EntityCollisionContext.class, "beryllium$redirectInstanceOf"));

		return probes;
	}

	/**
	 * True when the target class carries the merged {@code @Unique} marker field (or
	 * any other {@code beryllium$}-prefixed member) — the evidence available for the
	 * {@code @Overwrite}-style mixins, which replace whole vanilla methods and
	 * therefore merge no named handler.
	 */
	private static boolean hasBerylliumMember(Class<?> target) {
		try {
			for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
				for (java.lang.reflect.Field field : c.getDeclaredFields()) {
					if (field.getName().startsWith("beryllium$")) {
						return true;
					}
				}
				for (Method method : c.getDeclaredMethods()) {
					if (method.getName().startsWith("beryllium$")) {
						return true;
					}
				}
			}
		} catch (Throwable ignored) {
			// unresolved -> reported missing
		}
		return false;
	}

	/**
	 * True when the target class carries a merged handler method with the given name
	 * prefix. Walks the class hierarchy because Mixin may merge into a superclass,
	 * and falls back to null-safety on every step.
	 */
	private static boolean hasHandler(Class<?> target, String handlerPrefix) {
		try {
			for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
				for (Method method : c.getDeclaredMethods()) {
					if (method.getName().startsWith(handlerPrefix)) {
						return true;
					}
				}
			}
		} catch (Throwable ignored) {
			// unresolved -> reported missing
		}
		return false;
	}
}
