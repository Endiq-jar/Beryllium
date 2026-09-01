package com.endiq.beryllium.mixin.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.chunk.SectionPacking;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Phase 4 — the *dispatcher-level* dirty-mark interception of the chunk rebuild
 * prioritization queue, targeted at the real 1.21.4 pipeline.
 *
 * <p>The 1.21.2 renderer refactor removed {@code SectionRenderDispatcher#setSectionDirty}
 * entirely (verified against a 1.21.4 Mojang-mapped decompile: the class only exposes
 * {@code setLevel}/{@code setCamera}/{@code schedule}/{@code uploadAllPendingUploads}
 * and the inner {@code RenderSection} API). Every dynamic dirtiness mark now funnels
 * through {@code LevelRenderer.setSectionDirty} ({@link LevelRendererMixin}, the
 * public entry point) into {@code ViewArea#setDirty(int, int, int, boolean)} — the
 * 1.21.4 delegate that looks the section up and calls {@code RenderSection.setDirty}.
 * This mixin intercepts that delegate, so any caller that marks a section directly —
 * not just through LevelRenderer — is caught as well. The section is parked in
 * {@link ChunkRebuildManager}'s priority queue instead of being scheduled
 * immediately; the per-frame drain re-triggers it through vanilla's own
 * {@code setDirty}/{@code setSectionDirty} via {@link #beryllium$rescheduleDirty},
 * guarded by the manager's bypass flag.
 *
 * <p>Deliberately NOT intercepting {@code SectionRenderDispatcher.RenderSection#setDirty}:
 * that is the leaf mark, also called by the async compile-completion path, and
 * cancelling it would fight the rebuild pipeline itself. {@code ViewArea.setDirty}
 * returns void, so cancelling is safe — the same analysis as {@link LevelRendererMixin}.
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {

	/**
	 * Re-triggers a parked section through vanilla's own dirty-marking path, so the
	 * actual (asynchronous) rebuild is always scheduled by vanilla.
	 *
	 * <p>Reflection-based rather than a direct call so that a signature mismatch with
	 * the running version can never break the mod's class loading. Candidates are
	 * tried in order, against the captured reference first and then against the live
	 * {@code Minecraft.levelRenderer}: {@code ViewArea.setDirty(int, int, int,
	 * boolean)}, {@code LevelRenderer.setSectionDirty(int, int, int, boolean)}
	 * (private), then the public {@code LevelRenderer.setSectionDirty(int, int, int)}.
	 * {@code getDeclaredMethod} + {@code setAccessible} are used so private vanilla
	 * variants are reachable too; the class hierarchy is walked so a subclass (e.g. a
	 * renderer replacement) still resolves the vanilla methods. If all are missing it
	 * returns false and the caller drops the request (the same mismatch would also
	 * have made the {@code @Inject}s silent no-ops, so this path is only ever reached
	 * when at least one candidate genuinely exists).
	 *
	 * @return true if one of vanilla's dirty-marking variants was invoked.
	 */
	@Unique
	public static boolean beryllium$rescheduleDirty(Object rendererRef, long sectionPos, boolean important) {
		if (rendererRef == null) {
			return false;
		}
		try {
			int sx = SectionPacking.x(sectionPos);
			int sy = SectionPacking.y(sectionPos);
			int sz = SectionPacking.z(sectionPos);

			if (tryInvoke(rendererRef, sx, sy, sz, important)) {
				return true;
			}
			// The captured reference may be stale (e.g. a ViewArea from a previous
			// renderer); always fall back to the live LevelRenderer.
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null && minecraft.levelRenderer != null) {
				return tryInvoke(minecraft.levelRenderer, sx, sy, sz, important);
			}
			BerylliumLog.debug("[BERYLLIUM-CHUNK] no setDirty/setSectionDirty variant reachable; dropping re-trigger.");
			return false;
		} catch (ReflectiveOperationException | RuntimeException e) {
			BerylliumLog.debug("[BERYLLIUM-CHUNK] dirty-mark re-trigger failed: " + e);
			return false;
		}
	}

	@Unique
	private static boolean tryInvoke(Object target, int sx, int sy, int sz, boolean important)
		throws ReflectiveOperationException {
		// 1) ViewArea.setDirty(int, int, int, boolean) — the 1.21.4 delegate that
		//    LevelRenderer.setSectionDirty funnels into.
		Method m = findAny(target.getClass(), "setDirty", int.class, int.class, int.class, boolean.class);
		if (m != null) {
			m.invoke(target, sx, sy, sz, important);
			return true;
		}
		// 2) LevelRenderer.setSectionDirty(int, int, int, boolean) — private.
		m = findAny(target.getClass(), "setSectionDirty", int.class, int.class, int.class, boolean.class);
		if (m != null) {
			m.invoke(target, sx, sy, sz, important);
			return true;
		}
		// 3) LevelRenderer.setSectionDirty(int, int, int) — public, non-urgent.
		m = findAny(target.getClass(), "setSectionDirty", int.class, int.class, int.class);
		if (m != null) {
			m.invoke(target, sx, sy, sz);
			return true;
		}
		return false;
	}

	@Unique
	private static Method findAny(Class<?> clazz, String name, Class<?>... params) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Method m = c.getDeclaredMethod(name, params);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException e) {
				// keep walking up the hierarchy
			}
		}
		return null;
	}

	@Inject(method = "setDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void beryllium$queuePrioritizedSectionDirty(
		int sectionX, int sectionY, int sectionZ, boolean important, CallbackInfo ci
	) {
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

		manager.setRendererRef((Object) this);
		if (manager.enqueue(SectionPacking.pack(sectionX, sectionY, sectionZ), important)) {
			ci.cancel();
		}
	}
}
