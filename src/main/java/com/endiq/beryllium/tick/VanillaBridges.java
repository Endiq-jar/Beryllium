package com.endiq.beryllium.tick;

import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Method;

/**
 * Reflective bridges to the two vanilla bodies Beryllium needs to be able to invoke from
 * a worker thread: {@code ServerLevel#tickChunk} (random ticks) and
 * {@code Level#tickNonPassenger} (the per-entity tick).
 *
 * <p>Reflection is used on purpose, not because the methods are private, but because it
 * makes the whole feature degrade instead of break: if either name or signature differs on
 * the running Minecraft version, the lookup simply fails, Beryllium logs it once, and the
 * corresponding feature stays off. A direct (compile-time) call would turn the same drift
 * into a hard failure during class transformation.
 *
 * <p>Resolution happens once; after that it is a plain {@code Method#invoke}, which is far
 * cheaper than the work being invoked.
 */
public final class VanillaBridges {
	private static volatile Method tickChunkMethod;
	private static volatile Method tickNonPassengerMethod;
	private static volatile Method entityLevelMethod;
	private static volatile boolean resolvedTickChunk;
	private static volatile boolean resolvedTickNonPassenger;
	private static volatile boolean resolvedEntityLevel;

	private VanillaBridges() {
	}

	public static boolean hasTickChunk(ServerLevel level) {
		return resolveTickChunk(level);
	}

	/**
	 * @return true if vanilla's random-tick body was located and invoked.
	 */
	public static boolean tickChunk(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
		Method method = resolveTickChunk(level) ? tickChunkMethod : null;
		if (method == null) {
			return false;
		}
		try {
			method.invoke(level, chunk, randomTickSpeed);
			return true;
		} catch (Throwable t) {
			DeferredWorldActions.noteFailure(t);
			return false;
		}
	}

	public static boolean hasTickNonPassenger(Level level) {
		return resolveTickNonPassenger(level);
	}

	/**
	 * @return true if vanilla's per-entity tick body was located and invoked.
	 */
	public static boolean tickNonPassenger(Level level, Entity entity) {
		Method method = resolveTickNonPassenger(level) ? tickNonPassengerMethod : null;
		if (method == null) {
			return false;
		}
		try {
			method.invoke(level, entity);
			return true;
		} catch (Throwable t) {
			DeferredWorldActions.noteFailure(t);
			return false;
		}
	}

	/**
	 * The world an entity is in, or {@code null} when it cannot be determined.
	 *
	 * <p>Resolved reflectively because the accessor was renamed in the middle of the
	 * supported range ({@code getCommandSenderWorld} on older releases, {@code level} on
	 * 1.21.6+), and Beryllium compiles one source tree for all of them.
	 */
	public static Level entityLevel(Entity entity) {
		if (entity == null) {
			return null;
		}
		try {
			if (!resolvedEntityLevel) {
				synchronized (VanillaBridges.class) {
					if (!resolvedEntityLevel) {
						entityLevelMethod = findNoArg(entity.getClass(), "level", "getCommandSenderWorld");
						resolvedEntityLevel = true;
					}
				}
			}
			Method method = entityLevelMethod;
			if (method == null) {
				return null;
			}
			Object world = method.invoke(entity);
			return world instanceof Level level ? level : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean resolveTickChunk(ServerLevel level) {
		if (resolvedTickChunk) {
			return tickChunkMethod != null;
		}
		synchronized (VanillaBridges.class) {
			if (!resolvedTickChunk) {
				tickChunkMethod = find(level == null ? null : level.getClass(), "tickChunk", LevelChunk.class, int.class);
				resolvedTickChunk = true;
				if (tickChunkMethod == null) {
					BerylliumLog.warn("[BERYLLIUM-TICK] ServerLevel#tickChunk(LevelChunk, int) not found; "
						+ "async random ticks stay disabled for this session.");
				}
			}
			return tickChunkMethod != null;
		}
	}

	private static boolean resolveTickNonPassenger(Level level) {
		if (resolvedTickNonPassenger) {
			return tickNonPassengerMethod != null;
		}
		synchronized (VanillaBridges.class) {
			if (!resolvedTickNonPassenger) {
				tickNonPassengerMethod = find(level == null ? null : level.getClass(), "tickNonPassenger", Entity.class);
				resolvedTickNonPassenger = true;
				if (tickNonPassengerMethod == null) {
					BerylliumLog.warn("[BERYLLIUM-TICK] Level#tickNonPassenger(Entity) not found; "
						+ "parallel entity ticking stays disabled for this session.");
				}
			}
			return tickNonPassengerMethod != null;
		}
	}

	private static Method find(Class<?> clazz, String name, Class<?>... parameters) {
		if (clazz == null) {
			return null;
		}
		for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
			try {
				Method method = current.getDeclaredMethod(name, parameters);
				method.setAccessible(true);
				return method;
			} catch (NoSuchMethodException ignored) {
				// keep walking the hierarchy
			}
		}
		return null;
	}

	/** First declared no-arg method matching one of the names anywhere up the hierarchy. */
	private static Method findNoArg(Class<?> clazz, String... names) {
		for (String name : names) {
			Method method = find(clazz, name);
			if (method != null) {
				return method;
			}
		}
		return null;
	}
}
