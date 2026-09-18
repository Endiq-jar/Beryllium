package com.endiq.beryllium.culling;

import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Method;

/**
 * Version-proof access to {@link Camera}.
 *
 * <p>The camera's getters were renamed to record-style accessors inside the range of
 * releases Beryllium ships on: {@code getPosition()} became {@code position()},
 * {@code getLookVector()} became {@code forwardVector()}, {@code getXRot()} became
 * {@code xRot()}, and the look vector's return type narrowed to {@code Vector3fc}. One
 * source tree compiles against a single release at a time, so calling either spelling
 * directly would fail to build on the other half of the range.
 *
 * <p>Each accessor is therefore resolved once, by name, against whichever spelling the
 * running version carries, and cached as a {@link Method}. After that it is a plain
 * {@code invoke} — tens of nanoseconds, against the per-frame culling work that follows it,
 * and far cheaper than a second copy of every renderer class.
 *
 * <p>If no spelling resolves, the lookups answer {@code null} / a zero vector and the
 * callers fall back to their conservative behaviour (which is to cull nothing), so a future
 * rename can never make Beryllium hide something that should be visible.
 */
public final class CameraAccess {
	private static final Vector3f SCRATCH = new Vector3f();

	private static volatile Method currentCameraMethod;
	private static volatile boolean resolvedCurrentCamera;
	private static volatile Method positionMethod;
	private static volatile Method lookVectorMethod;
	private static volatile Method xRotMethod;
	private static volatile Method yRotMethod;
	private static volatile boolean resolved;

	private CameraAccess() {
	}

	/**
	 * The live camera.
	 *
	 * <p>{@code GameRenderer}'s accessor for it was renamed too (and is not one of the names
	 * the rest of the class resolves), so it is located by shape rather than by name: any
	 * no-argument method on the game renderer whose return type is a {@link Camera} will do.
	 * The camera itself is then read through the resolved accessors above.
	 *
	 * @return the camera, or null when there is none (early startup, no world, or no such
	 *         method on this release)
	 */
	public static Camera current() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) {
			return null;
		}
		resolve();
		if (!resolvedCurrentCamera) {
			synchronized (CameraAccess.class) {
				if (!resolvedCurrentCamera) {
					currentCameraMethod = findCameraOn(minecraft.gameRenderer.getClass());
					resolvedCurrentCamera = true;
				}
			}
		}
		Method method = currentCameraMethod;
		if (method == null) {
			return null;
		}
		try {
			Object value = method.invoke(minecraft.gameRenderer);
			return value instanceof Camera camera ? camera : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static Method findCameraOn(Class<?> gameRendererType) {
		Method fallback = null;
		for (Method candidate : gameRendererType.getMethods()) {
			if (candidate.getParameterCount() != 0 || !Camera.class.isAssignableFrom(candidate.getReturnType())) {
				continue;
			}
			String name = candidate.getName();
			if (name.equals("mainCamera") || name.equals("getMainCamera") || name.equals("camera")) {
				return candidate;
			}
			if (fallback == null) {
				fallback = candidate;
			}
		}
		return fallback;
	}

	/** @return the camera position, or null when the running release exposes neither spelling */
	public static Vec3 position(Camera camera) {
		resolve();
		Method method = positionMethod;
		if (camera == null || method == null) {
			return null;
		}
		try {
			Object value = method.invoke(camera);
			return value instanceof Vec3 vec ? vec : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * The camera's forward vector.
	 *
	 * <p>The result is a shared scratch vector: read it immediately, do not retain it. It is
	 * returned as a {@link Vector3f} on every release even though newer ones hand back a
	 * {@code Vector3fc}, so callers do not have to care which.
	 *
	 * @return the forward vector, or the zero vector when unavailable
	 */
	public static Vector3f lookVector(Camera camera) {
		resolve();
		Method method = lookVectorMethod;
		if (camera == null || method == null) {
			return SCRATCH.set(0.0f, 0.0f, 0.0f);
		}
		try {
			Object value = method.invoke(camera);
			if (value instanceof Vector3fc vector) {
				return SCRATCH.set(vector.x(), vector.y(), vector.z());
			}
		} catch (Throwable ignored) {
			// fall through to the zero vector
		}
		return SCRATCH.set(0.0f, 0.0f, 0.0f);
	}

	/** @return the camera's pitch, or {@link Float#NaN} when unavailable */
	public static float xRot(Camera camera) {
		resolve();
		Method method = xRotMethod;
		if (camera == null || method == null) {
			return Float.NaN;
		}
		try {
			Object value = method.invoke(camera);
			return value instanceof Number number ? number.floatValue() : Float.NaN;
		} catch (Throwable t) {
			return Float.NaN;
		}
	}

	/** @return the camera's yaw, or {@link Float#NaN} when unavailable */
	public static float yRot(Camera camera) {
		resolve();
		Method method = yRotMethod;
		if (camera == null || method == null) {
			return Float.NaN;
		}
		try {
			Object value = method.invoke(camera);
			return value instanceof Number number ? number.floatValue() : Float.NaN;
		} catch (Throwable t) {
			return Float.NaN;
		}
	}

	private static void resolve() {
		if (resolved) {
			return;
		}
		synchronized (CameraAccess.class) {
			if (resolved) {
				return;
			}
			positionMethod = find("position", "getPosition");
			lookVectorMethod = find("forwardVector", "getLookVector");
			xRotMethod = find("xRot", "getXRot");
			yRotMethod = find("yRot", "getYRot");
			resolved = true;

			if (positionMethod == null || lookVectorMethod == null) {
				BerylliumLog.warn("[BERYLLIUM-CULL] Camera position/look accessors not found; "
						+ "camera-relative culling degrades to vanilla for this session.");
			}
		}
	}

	private static Method find(String... names) {
		for (String name : names) {
			try {
				Method method = Camera.class.getMethod(name);
				if (method.getParameterCount() == 0) {
					return method;
				}
			} catch (Throwable ignored) {
				// try the next spelling this release might use
			}
		}
		return null;
	}
}
