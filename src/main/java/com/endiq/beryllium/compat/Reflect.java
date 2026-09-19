package com.endiq.beryllium.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small, cached reflection helper, used where a direct reference would tie this mod to the class
 * layout of one Minecraft release.
 *
 * <p>Beryllium ships one jar per release from a single source tree, so every type it names
 * directly has to exist in <em>all</em> of them. Where a class or a member moved inside the
 * supported range — the chat message types, the HUD, the sound engine's internals, the profiler
 * — the code reaches it through here instead. The cost is one lookup per member per session, and
 * the benefit is that a rename can only ever disable a feature rather than fail the build or,
 * worse, fail at load.
 *
 * <p>Every method returns {@code null}/{@code false} instead of throwing: a caller that cannot
 * find what it needs falls back to doing what vanilla does.
 */
public final class Reflect {
	private Reflect() {
	}

	private static final Object MISSING = new Object();
	private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

	/** @return the class, or null when it is not in this release at all. */
	public static Class<?> type(String name) {
		try {
			return Class.forName(name);
		} catch (Throwable t) {
			return null;
		}
	}

	/** True when this release has the named class. */
	public static boolean hasType(String name) {
		return type(name) != null;
	}

	// --- fields ------------------------------------------------------------------------

	/** Reads a field (walking up the class hierarchy and into static fields). */
	public static Object get(Object target, String fieldName) {
		if (target == null) {
			return null;
		}
		try {
			Field field = findField(target.getClass(), fieldName);
			return field == null ? null : field.get(target);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Reads a static field off a class. */
	public static Object getStatic(Class<?> owner, String fieldName) {
		if (owner == null) {
			return null;
		}
		try {
			Field field = findField(owner, fieldName);
			return field == null ? null : field.get(null);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Writes a field. */
	public static boolean set(Object target, String fieldName, Object value) {
		if (target == null) {
			return false;
		}
		try {
			Field field = findField(target.getClass(), fieldName);
			if (field == null) {
				return false;
			}
			field.set(target, value);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** @return the field, or null. Cached, including the "not there" answer. */
	public static Field findField(Class<?> owner, String fieldName) {
		if (owner == null || fieldName == null) {
			return null;
		}
		String key = "F:" + owner.getName() + '#' + fieldName;
		Object cached = CACHE.computeIfAbsent(key, unused -> {
			for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
				try {
					Field field = current.getDeclaredField(fieldName);
					try {
						field.setAccessible(true);
					} catch (Throwable ignored) {
						// Some JDK types refuse it; the field may still be readable.
					}
					return field;
				} catch (Throwable ignored) {
					// keep walking: the field may be declared further up
				}
			}
			return MISSING;
		});
		return cached == MISSING ? null : (Field) cached;
	}

	// --- methods -----------------------------------------------------------------------

	/** Calls a method with no arguments. */
	public static Object call(Object target, String methodName) {
		return call(target, methodName, new Object[0], new Class<?>[0]);
	}

	/**
	 * Calls a method by name, choosing the overload by argument count and by what the arguments
	 * can actually be assigned to. Numbers are widened as needed, which is what makes this
	 * usable across releases that disagree about whether something is an {@code int} or a
	 * {@code float}.
	 */
	public static Object call(Object target, String methodName, Object... args) {
		if (target == null) {
			return null;
		}
		return callStaticOrInstance(target.getClass(), target, methodName, args == null ? new Object[0] : args);
	}

	/** Calls a static method on a class. */
	public static Object callStatic(Class<?> owner, String methodName, Object... args) {
		if (owner == null) {
			return null;
		}
		return callStaticOrInstance(owner, null, methodName, args == null ? new Object[0] : args);
	}

	private static Object callStaticOrInstance(Class<?> owner, Object target, String methodName, Object[] args) {
		Method method = findMethod(owner, methodName, args.length, args);
		if (method == null) {
			return null;
		}
		try {
			return method.invoke(Modifier.isStatic(method.getModifiers()) ? null : target, coerceArgs(method, args));
		} catch (Throwable t) {
			return null;
		}
	}

	/** @return a matching method, or null when this release has none of that name/arity. */
	public static Method findMethod(Class<?> owner, String methodName, int argCount, Object[] args) {
		if (owner == null || methodName == null) {
			return null;
		}
		String key = "M:" + owner.getName() + '#' + methodName + '/' + argCount;
		Object cached = CACHE.computeIfAbsent(key, unused -> {
			Method found = search(owner, methodName, argCount);
			return found == null ? MISSING : found;
		});
		return cached == MISSING ? null : (Method) cached;
	}

	/**
	 * Walks the class and its supertypes for a member of that name and arity.
	 *
	 * <p>Deliberately not cached itself, and deliberately not recursive through
	 * {@link #findMethod}: filling a cache from inside its own loader is how a concurrent map ends
	 * up being updated recursively, and the search is only ever performed once per class.
	 */
	private static Method search(Class<?> owner, String methodName, int argCount) {
		for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
			Method found = declared(current, methodName, argCount);
			if (found != null) {
				return found;
			}
		}
		for (Class<?> iface : owner.getInterfaces()) {
			Method found = search(iface, methodName, argCount);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static Method declared(Class<?> owner, String methodName, int argCount) {
		Method candidate = null;
		try {
			for (Method method : owner.getDeclaredMethods()) {
				if (!method.getName().equals(methodName) || method.getParameterCount() != argCount) {
					continue;
				}
				if (candidate == null) {
					candidate = method;
				}
				try {
					method.setAccessible(true);
				} catch (Throwable ignored) {
					// Some JDK types refuse it; the method may still be invokable.
				}
			}
		} catch (Throwable t) {
			return null;
		}
		return candidate;
	}

	private static Object[] coerceArgs(Method method, Object[] args) {
		Class<?>[] types = method.getParameterTypes();
		if (types.length != args.length) {
			return args;
		}
		Object[] converted = new Object[args.length];
		for (int i = 0; i < types.length; i++) {
			converted[i] = coerce(types[i], args[i]);
		}
		return converted;
	}

	private static Object coerce(Class<?> type, Object value) {
		if (value == null) {
			return null;
		}
		if (type.isInstance(value)) {
			return value;
		}
		if (!(value instanceof Number number)) {
			return value;
		}
		if (type == int.class || type == Integer.class) {
			return number.intValue();
		}
		if (type == long.class || type == Long.class) {
			return number.longValue();
		}
		if (type == float.class || type == Float.class) {
			return number.floatValue();
		}
		if (type == double.class || type == Double.class) {
			return number.doubleValue();
		}
		if (type == short.class || type == Short.class) {
			return number.shortValue();
		}
		if (type == byte.class || type == Byte.class) {
			return number.byteValue();
		}
		return value;
	}

	/** Convenience: the string form of a possibly-not-a-String value. */
	public static String asString(Object value) {
		return value instanceof String text ? text : null;
	}

	/** Convenience: a boolean that treats "not there" as false. */
	public static boolean asBoolean(Object value) {
		return value instanceof Boolean flag && flag;
	}
}
