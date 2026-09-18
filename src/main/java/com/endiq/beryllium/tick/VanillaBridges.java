package com.endiq.beryllium.tick;

import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Field;
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
	private static volatile Method chunkMinBlockXMethod;
	private static volatile Method chunkMinBlockZMethod;
	private static volatile Method chunkXMethod;
	private static volatile Method chunkZMethod;
	private static volatile Field chunkXField;
	private static volatile Field chunkZField;
	private static volatile boolean resolvedChunkPos;
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
	 * The entity's own level.
	 *
	 * <p>{@code Entity#getCommandSenderWorld()} is the name this was written against, but the
	 * getter Mojang uses for "the level I am in" has been renamed since, and Beryllium is
	 * compiled against one release at a time while shipping every release. The accessor is
	 * therefore resolved once by name against whichever of the known spellings the running
	 * version carries, and cached; if none of them exist the caller treats the entity as
	 * "no level" and leaves it to vanilla.
	 */
	public static Level levelOf(Entity entity) {
		if (entity == null) {
			return null;
		}
		Method method = resolveEntityLevel(entity.getClass());
		if (method == null) {
			return null;
		}
		try {
			return (Level) method.invoke(entity);
		} catch (Throwable t) {
			return null;
		}
	}

	private static final String[] LEVEL_ACCESSORS = {
		"getCommandSenderWorld", "level", "getLevel", "getWorld", "commandSenderWorld"
	};

	/**
	 * A chunk's X coordinate.
	 *
	 * <p>{@code ChunkPos#x} is a public field on most releases but not all of them, so the
	 * value is read through whichever accessor this release actually has
	 * ({@code getMinBlockX()} and friends). If none resolves, every chunk reports 0 — which
	 * puts every chunk in the same colouring round and therefore runs them one at a time,
	 * exactly like vanilla. Wrong answers would be dangerous; a slow answer is not.
	 */
	public static int chunkX(LevelChunk chunk) {
		return chunkCoordinate(chunk, true);
	}

	/** A chunk's Z coordinate. See {@link #chunkX(LevelChunk)}. */
	public static int chunkZ(LevelChunk chunk) {
		return chunkCoordinate(chunk, false);
	}

	private static int chunkCoordinate(LevelChunk chunk, boolean xAxis) {
		if (chunk == null) {
			return 0;
		}
		resolveChunkPos();
		Object pos;
		try {
			pos = chunk.getPos();
		} catch (Throwable t) {
			return 0;
		}
		if (pos == null) {
			return 0;
		}
		try {
			if (xAxis) {
				if (chunkMinBlockXMethod != null) {
					return ((Number) chunkMinBlockXMethod.invoke(pos)).intValue() >> 4;
				}
				if (chunkXMethod != null) {
					return ((Number) chunkXMethod.invoke(pos)).intValue();
				}
				if (chunkXField != null) {
					return chunkXField.getInt(pos);
				}
			} else {
				if (chunkMinBlockZMethod != null) {
					return ((Number) chunkMinBlockZMethod.invoke(pos)).intValue() >> 4;
				}
				if (chunkZMethod != null) {
					return ((Number) chunkZMethod.invoke(pos)).intValue();
				}
				if (chunkZField != null) {
					return chunkZField.getInt(pos);
				}
			}
		} catch (Throwable ignored) {
			// fall through to the conservative answer below
		}
		return 0;
	}

	private static void resolveChunkPos() {
		if (resolvedChunkPos) {
			return;
		}
		synchronized (VanillaBridges.class) {
			if (resolvedChunkPos) {
				return;
			}
			Class<?> posClass = null;
			try {
				posClass = Class.forName("net.minecraft.world.level.ChunkPos");
			} catch (Throwable ignored) {
				// leave everything null
			}
			if (posClass != null) {
				chunkMinBlockXMethod = find(posClass, "getMinBlockX");
				chunkMinBlockZMethod = find(posClass, "getMinBlockZ");
				chunkXMethod = find(posClass, "getX");
				chunkZMethod = find(posClass, "getZ");
				chunkXField = findField(posClass, "x");
				chunkZField = findField(posClass, "z");
			}
			resolvedChunkPos = true;
		}
	}

	private static Field findField(Class<?> clazz, String name) {
		for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (Throwable ignored) {
				// keep walking the hierarchy
			}
		}
		return null;
	}

	private static Method resolveEntityLevel(Class<?> type) {
		if (resolvedEntityLevel) {
			return entityLevelMethod;
		}
		synchronized (VanillaBridges.class) {
			if (!resolvedEntityLevel) {
				for (String name : LEVEL_ACCESSORS) {
					Method candidate = find(type, name);
					if (candidate != null && Level.class.isAssignableFrom(candidate.getReturnType())) {
						entityLevelMethod = candidate;
						break;
					}
				}
				resolvedEntityLevel = true;
				if (entityLevelMethod == null) {
					BerylliumLog.warn("[BERYLLIUM-TICK] no Entity level accessor found; "
							+ "parallel entity ticking and item throttling stay disabled for this session.");
				}
			}
			return entityLevelMethod;
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
}
