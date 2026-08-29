package com.endiq.beryllium.mixin.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.chunk.SectionPacking;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * NOT COMPILE-VERIFIED — same sandbox caveat as the other mixins: this targets
 * {@code SectionRenderDispatcher.setSectionDirty(long, boolean)} by string descriptor
 * with {@code require = 0}, so a 1.21.4 signature mismatch silently keeps vanilla
 * behavior (no crash). Verify against a decompile of {@code SectionRenderDispatcher}
 * (class renamed from {@code ChunkRenderDispatcher} in 1.21.2; the dirty-mark method
 * survived the rename).
 *
 * <p>Phase 4 — the *dispatcher-level* dirty-mark interception of the chunk rebuild
 * prioritization queue. {@code ViewArea} funnels section dirtiness through this method,
 * so intercepting here (together with {@link LevelRendererMixin}, which catches the
 * public entry points) captures every dynamic rebuild trigger. The section is parked in
 * {@link ChunkRebuildManager}'s priority queue instead of being scheduled immediately;
 * the per-frame drain re-triggers it through vanilla's own {@code setSectionDirty} via
 * {@link #beryllium$rescheduleDirty}, guarded by the manager's bypass flag.
 *
 * <p>Deliberately NOT intercepting {@code scheduleRebuild} itself: that method returns a
 * {@code SectionTask} that callers may hold, and cancelling it would leave those callers
 * with a null where vanilla promised an object. {@code setSectionDirty} returns void, so
 * cancelling is safe. Initial-load scheduling (which flows through
 * {@code updateChunks -> scheduleRebuild}) is likewise left to vanilla — this feature
 * reorders the *dynamic* dirtiness flood (mining, building, redstone), which is the
 * per-frame lag source; initial load is a separate one-time mesh storm.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {

	/**
	 * Re-triggers a parked section through vanilla's own dirty-marking path, so the
	 * actual (asynchronous) rebuild is always scheduled by vanilla.
	 *
	 * <p>Reflection-based rather than a direct call so that a signature mismatch with the
	 * running version can never break the mod's class loading. Candidate entry points are
	 * tried in order — the dispatcher's long overload, its unpacked-coordinates overload,
	 * then the {@code LevelRenderer} overloads (long+boolean, int+int+int+boolean, and
	 * the no-boolean int+int+int variant). {@code getDeclaredMethod} + {@code setAccessible}
	 * are used so private vanilla variants are reachable too. If all are missing it returns
	 * false and the caller drops the request (the same mismatch would also have made the
	 * {@code @Inject} below a silent no-op, so this path is only ever reached when at
	 * least one candidate genuinely exists).
	 *
	 * @return true if one of vanilla's {@code setSectionDirty} variants was invoked.
	 */
	@Unique
	public static boolean beryllium$rescheduleDirty(Object dispatcher, long sectionPos, boolean important) {
		if (dispatcher == null) {
			return false;
		}
		try {
			int sx = SectionPacking.x(sectionPos);
			int sy = SectionPacking.y(sectionPos);
			int sz = SectionPacking.z(sectionPos);

			// 1) Dispatcher-level: setSectionDirty(long, boolean) — what the mixin injects into.
			Method m = findAny(dispatcher.getClass(), "setSectionDirty", long.class, boolean.class);
			if (m != null) {
				m.invoke(dispatcher, sectionPos, important);
				return true;
			}
			// 2) Dispatcher-level with unpacked section coordinates.
			m = findAny(dispatcher.getClass(), "setSectionDirty", int.class, int.class, int.class, boolean.class);
			if (m != null) {
				m.invoke(dispatcher, sx, sy, sz, important);
				return true;
			}
			// 3) LevelRenderer entry points (the public face of dynamic dirtiness).
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null && minecraft.levelRenderer != null) {
				Object levelRenderer = minecraft.levelRenderer;
				m = findAny(levelRenderer.getClass(), "setSectionDirty", long.class, boolean.class);
				if (m != null) {
					m.invoke(levelRenderer, sectionPos, important);
					return true;
				}
				m = findAny(levelRenderer.getClass(), "setSectionDirty", int.class, int.class, int.class, boolean.class);
				if (m != null) {
					m.invoke(levelRenderer, sx, sy, sz, important);
					return true;
				}
				m = findAny(levelRenderer.getClass(), "setSectionDirty", int.class, int.class, int.class);
				if (m != null) {
					m.invoke(levelRenderer, sx, sy, sz);
					return true;
				}
			}
			BerylliumLog.debug("[BERYLLIUM-CHUNK] no setSectionDirty variant reachable on "
				+ dispatcher.getClass().getSimpleName() + "; dropping re-trigger.");
			return false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			BerylliumLog.debug("[BERYLLIUM-CHUNK] setSectionDirty re-trigger failed: " + e);
			return false;
		}
	}

	@Unique
	private static Method findAny(Class<?> clazz, String name, Class<?>... params) {
		try {
			Method m = clazz.getDeclaredMethod(name, params);
			m.setAccessible(true);
			return m;
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	@Inject(method = "setSectionDirty(JZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$queuePrioritizedSectionDirty(long sectionPos, boolean important, CallbackInfo ci) {
		ChunkRebuildManager manager = ChunkRebuildManager.instance();
		if (manager == null || manager.isBypassing()) {
			return;
		}
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.chunkRebuildPrioritization) {
			return;
		}
		if (Beryllium.isChunkOptimizationDeferredToOtherMod()) {
			return;
		}

		manager.setDispatcher((Object) this);
		if (manager.enqueue(sectionPos, important)) {
			ci.cancel();
		}
	}
}
