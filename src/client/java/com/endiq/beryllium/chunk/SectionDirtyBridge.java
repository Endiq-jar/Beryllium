package com.endiq.beryllium.chunk;

import com.endiq.beryllium.compat.MappedNames;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * against the live {@code Minecraft.levelRenderer}: {@code ViewArea.setDirty(int, int, int,
 * boolean)} (the 1.21.2+ delegate that {@code LevelRenderer.setSectionDirty} funnels into),
 * {@code LevelRenderer.setSectionDirty(int, int, int, boolean)} (private, the urgent
 * overload), then {@code LevelRenderer.setSectionDirty(int, int, int)} (public, older
 * releases). Each candidate is resolved three ways, in order:
 * <ol>
 *   <li>through Fabric's {@link MappedNames}, because a Fabric production runtime calls
 *       Minecraft members by their intermediary names and a Mojang-spelled string does not
 *       match anything there;</li>
 *   <li>by the literal Mojang name, which is what a development run and every unobfuscated
 *       26.1+ release actually carry;</li>
 *   <li>by shape alone — the one {@code void} method with exactly those parameter types —
 *       which survives any mapping, and which deliberately refuses to answer when the shape
 *       is not unique rather than guess.</li>
 * </ol>
 *
 * <p>{@link #canReschedule(Object)} is the reason the queue is safe. Intercepting a dirty
 * mark only pays off if the mark can be handed back later; a queue that accepts a request
 * and then cannot re-trigger it has <em>lost</em> that update, and the section stays stale
 * until something else happens to dirty it again. The manager therefore refuses to
 * intercept at all when this reports false, and vanilla keeps its own behaviour.
 *
 * <p>The shape fallback was checked against every renderer era in the supported range:
 * {@code void(int, int, int, boolean)} is {@code setSectionDirty} and nothing else on
 * {@code LevelRenderer}, and {@code setDirty} and nothing else on {@code ViewArea}, so
 * accepting a unique match cannot call the wrong method. The three-int shape is not unique
 * on {@code LevelRenderer} ({@code setSectionDirtyWithNeighbors} shares it), which is
 * exactly why an ambiguous shape is refused rather than guessed.
 */
public final class SectionDirtyBridge {
	private static final String VIEW_AREA = "net.minecraft.client.renderer.ViewArea";
	private static final String LEVEL_RENDERER = "net.minecraft.client.renderer.LevelRenderer";

	/** Resolved re-trigger per renderer class; {@link #NONE} once we know there isn't one. */
	private static final Map<Class<?>, Method> RESOLVED = new ConcurrentHashMap<>();
	private static final Method NONE = noneSentinel();

	private static volatile boolean failureLogged;

	private SectionDirtyBridge() {
	}

	/**
	 * True when a parked section can be handed back to vanilla on this release. Cached per
	 * renderer class, so the steady-state cost is one map lookup.
	 */
	public static boolean canReschedule(Object rendererRef) {
		return resolve(rendererRef) != null;
	}

	/**
	 * Hands a parked section back to vanilla.
	 *
	 * @return true if one of vanilla's dirty-marking variants was invoked
	 */
	public static boolean rescheduleDirty(Object rendererRef, long sectionPos, boolean important) {
		if (rendererRef == null) {
			return false;
		}
		try {
			int sx = SectionPacking.x(sectionPos);
			int sy = SectionPacking.y(sectionPos);
			int sz = SectionPacking.z(sectionPos);

			Method method = resolve(rendererRef);
			if (method != null && invoke(method, rendererRef, sx, sy, sz, important)) {
				return true;
			}
			// The captured reference may be stale (a ViewArea from a previous renderer);
			// always fall back to the live LevelRenderer.
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null && minecraft.levelRenderer != null && minecraft.levelRenderer != rendererRef) {
				Method fallback = resolve(minecraft.levelRenderer);
				if (fallback != null && invoke(fallback, minecraft.levelRenderer, sx, sy, sz, important)) {
					return true;
				}
			}
			logFailureOnce("no setDirty/setSectionDirty variant reachable");
			return false;
		} catch (RuntimeException e) {
			logFailureOnce("dirty-mark re-trigger failed: " + e);
			return false;
		}
	}

	/**
	 * Finds the dirty-marking entry point this renderer actually carries, or null when it
	 * carries none of them.
	 */
	private static Method resolve(Object rendererRef) {
		if (rendererRef == null) {
			return null;
		}
		Class<?> type = rendererRef.getClass();
		Method cached = RESOLVED.get(type);
		if (cached != null) {
			return cached == NONE ? null : cached;
		}
		Method found = search(type);
		RESOLVED.put(type, found == null ? NONE : found);
		if (found == null) {
			logFailureOnce("no dirty-mark entry point on " + type.getName());
		} else {
			BerylliumLog.debug("[BERYLLIUM-CHUNK] Re-trigger entry point on " + type.getName()
				+ " resolved to " + found.getName() + describe(found));
		}
		return found;
	}

	private static Method search(Class<?> type) {
		// 1) ViewArea.setDirty(int, int, int, boolean) — the 1.21.2+ delegate.
		Method method = candidate(type, VIEW_AREA, "setDirty", "(IIIZ)V",
			new Class<?>[] {int.class, int.class, int.class, boolean.class});
		if (method != null) {
			return method;
		}
		// 2) LevelRenderer.setSectionDirty(int, int, int, boolean) — private, urgent.
		method = candidate(type, LEVEL_RENDERER, "setSectionDirty", "(IIIZ)V",
			new Class<?>[] {int.class, int.class, int.class, boolean.class});
		if (method != null) {
			return method;
		}
		// 3) LevelRenderer.setSectionDirty(int, int, int) — public, urgency unknown.
		return candidate(type, LEVEL_RENDERER, "setSectionDirty", "(III)V",
			new Class<?>[] {int.class, int.class, int.class});
	}

	private static Method candidate(
		Class<?> type, String namedOwner, String namedMethod, String namedDescriptor, Class<?>[] params
	) {
		// The runtime name, when this release's mappings know the member at all.
		Method method = Reflect.findMethod(type, MappedNames.methodName(namedOwner, namedMethod, namedDescriptor), params);
		if (method != null) {
			return method;
		}
		// A development run, and every unobfuscated 26.1+ release, carries the Mojang name.
		method = Reflect.findMethod(type, namedMethod, params);
		if (method != null) {
			return method;
		}
		// Last resort: the shape, which no mapping can rename away. Only accepted when it
		// identifies exactly one method — see Reflect#findUniqueVoidMethod.
		return Reflect.findUniqueVoidMethod(type, params);
	}

	private static boolean invoke(Method method, Object target, int sx, int sy, int sz, boolean important) {
		try {
			if (method.getParameterCount() == 4) {
				method.invoke(target, sx, sy, sz, important);
			} else {
				method.invoke(target, sx, sy, sz);
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	private static void logFailureOnce(String reason) {
		if (failureLogged) {
			return;
		}
		failureLogged = true;
		// This is the one case where the feature has to announce itself: the queue is
		// switched off rather than silently losing updates.
		BerylliumLog.warn("[BERYLLIUM-CHUNK] Chunk rebuild prioritization disabled for this session ("
			+ reason + "); vanilla schedules section rebuilds directly.");
	}

	private static String describe(Method method) {
		StringBuilder signature = new StringBuilder("(");
		for (Class<?> type : method.getParameterTypes()) {
			signature.append(type.getSimpleName()).append(',');
		}
		return signature.append(')').toString();
	}

	/** A {@link Method} cannot be fabricated, so the "known absent" marker is a real one. */
	private static Method noneSentinel() {
		try {
			return SectionDirtyBridge.class.getDeclaredMethod("noneSentinel");
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException("sentinel lookup failed", e);
		}
	}
}
