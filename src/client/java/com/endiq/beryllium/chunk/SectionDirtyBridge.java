package com.endiq.beryllium.chunk;

import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

/**
 * Re-triggers a parked chunk section through vanilla's own dirty-marking path, so the
 * rebuild itself is always scheduled and executed by vanilla.
 *
 * <p>This used to live inside the {@code ViewArea} mixin, which meant the reflection could
 * only be used on releases where that class exists. Moving it here lets the prioritization
 * queue work on every supported release: the queue decides <em>what order</em> sections are
 * rebuilt in, and this bridge hands them back to whichever dirty-marking entry point the
 * running version actually has.
 *
 * <p>Candidates are tried in order against the captured renderer reference first and then
 * against the live {@code Minecraft.levelRenderer}: {@code setDirty(int,int,int,boolean)}
 * (1.21.2+ {@code ViewArea}), {@code setSectionDirty(int,int,int,boolean)} (private, the
 * urgent {@code LevelRenderer} overload), then {@code setSectionDirty(int,int,int)}
 * (public, older releases).
 *
 * @return true if one of vanilla's variants was invoked
 */
public final class SectionDirtyBridge {
	private SectionDirtyBridge() {
	}

	public static boolean rescheduleDirty(Object rendererRef, long sectionPos, boolean important) {
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
			// The captured reference may be stale (a ViewArea from a previous renderer);
			// always fall back to the live LevelRenderer.
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

	private static boolean tryInvoke(Object target, int sx, int sy, int sz, boolean important)
		throws ReflectiveOperationException {
		// 1) ViewArea.setDirty(int, int, int, boolean) — the 1.21.2+ delegate that
		//    LevelRenderer.setSectionDirty funnels into.
		Method m = findAny(target.getClass(), "setDirty", int.class, int.class, int.class, boolean.class);
		if (m != null) {
			m.invoke(target, sx, sy, sz, important);
			return true;
		}
		// 2) LevelRenderer.setSectionDirty(int, int, int, boolean) — private, pre-1.21.2.
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
}
