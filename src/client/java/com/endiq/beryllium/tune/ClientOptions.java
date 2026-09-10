package com.endiq.beryllium.tune;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Phase 13 — the single reflective access layer for vanilla's client options.
 *
 * <p>Minecraft refactored its options system across the covered version range (the
 * {@code OptionInstance} family), and the exact accessor shape differs between
 * releases. Everything Beryllium does to options — the mobile preset, the max-FPS
 * preset, Dynamic FPS throttling, reading the render distance for fog-wall culling —
 * goes through this class so the reflection lives in exactly one place and every
 * caller gets the same degrade-to-no-op behavior: a field, method or value that
 * cannot be resolved returns {@code false}/{@code null} and leaves the game exactly
 * as vanilla left it. Nothing here can throw out to a caller.
 *
 * <p>Level access used by read-back (the integer value of an option, e.g.
 * renderDistance) walks the option instance for its {@code value} field by name
 * before falling back to type-based discovery, because a fuzzy "any Integer field"
 * scan can pick up unrelated integers (slider bounds, cached callbacks) and would
 * then compute e.g. a fog-wall plane from a minimum value rather than the actual
 * setting.
 */
public final class ClientOptions {
	private ClientOptions() {
	}

	/** The live options object, or null when there is no client yet. */
	public static Object options() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			return minecraft == null ? null : minecraft.options;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Sets an option by the name of its field on the options object, through the
	 * option instance's single-argument {@code set} method.
	 *
	 * @return true if the option was found and set; false (never an exception) otherwise
	 */
	public static boolean set(String fieldName, Object value) {
		try {
			Object options = options();
			if (options == null || fieldName == null || value == null) {
				return false;
			}
			Object instance = fieldValue(options, fieldName);
			if (instance == null) {
				return false;
			}
			Method set = findSingleArgMethod(instance.getClass(), "set");
			if (set == null) {
				return false;
			}
			set.invoke(instance, value);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Reads an integer-valued option (e.g. {@code renderDistance},
	 * {@code framerateLimit}, {@code simulationDistance}).
	 *
	 * @return the current value, or null when it cannot be resolved unambiguously
	 */
	public static Integer readInt(String fieldName) {
		try {
			Object options = options();
			if (options == null || fieldName == null) {
				return null;
			}
			return findIntegerValue(fieldValue(options, fieldName));
		} catch (Throwable t) {
			return null;
		}
	}

	/** Persists the options object to options.txt (best-effort). */
	public static void save() {
		invokeNoArg(options(), "save");
	}

	/** Finds a field by name on the object or any superclass (never throws). */
	public static Object fieldValue(Object target, String fieldName) {
		if (target == null || fieldName == null) {
			return null;
		}
		try {
			for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				Field field;
				try {
					field = c.getDeclaredField(fieldName);
				} catch (NoSuchFieldException e) {
					continue;
				}
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				return field.get(target);
			}
		} catch (Throwable ignored) {
			// unresolvable -> caller degrades
		}
		return null;
	}

	/**
	 * The current integer value stored in an option instance. Prefers the
	 * {@code value} field by name; a type-based fallback is deliberately not used
	 * because it can select an unrelated integer (see the class javadoc).
	 */
	public static Integer findIntegerValue(Object instance) {
		Object value = fieldValue(instance, "value");
		if (value instanceof Integer integer) {
			return integer;
		}
		return null;
	}

	public static Method findSingleArgMethod(Class<?> clazz, String name) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == 1) {
					method.setAccessible(true);
					return method;
				}
			}
		}
		return null;
	}

	public static void invokeNoArg(Object target, String name) {
		if (target == null) {
			return;
		}
		try {
			Class<?> clazz = target.getClass();
			while (clazz != null && clazz != Object.class) {
				try {
					Method method = clazz.getDeclaredMethod(name);
					method.setAccessible(true);
					method.invoke(target);
					return;
				} catch (NoSuchMethodException e) {
					clazz = clazz.getSuperclass();
				}
			}
		} catch (Throwable ignored) {
			// best-effort persistence
		}
	}
}
