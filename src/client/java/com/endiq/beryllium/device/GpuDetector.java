package com.endiq.beryllium.device;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Queries the current OpenGL context for vendor/renderer/version strings, the primary
 * monitor for its refresh rate, and GL_MAX_TEXTURE_SIZE (input to
 * {@code GraphicsCapabilityClassifier}).
 *
 * <p><b>Must be called on the render thread, after a GL context exists</b> — in practice,
 * from a listener on {@code ClientLifecycleEvents.CLIENT_STARTED} or later. Calling this
 * before the window is created will not crash (every lookup is wrapped), but will just
 * return "unknown"/-1 placeholders, since there's nothing to query yet.
 */
public final class GpuDetector {
	private GpuDetector() {
	}

	public static GpuInfo detect() {
		String vendor = safeGetString(GL11.GL_VENDOR);
		String renderer = safeGetString(GL11.GL_RENDERER);
		String glVersion = safeGetString(GL11.GL_VERSION);
		String glslVersion = safeGetString(GL20.GL_SHADING_LANGUAGE_VERSION);
		String graphicsApi = glVersion.toUpperCase().contains("OPENGL ES") ? "OpenGL ES" : "OpenGL";

		return new GpuInfo(vendor, renderer, glVersion, glslVersion, graphicsApi,
			safeGetRefreshRate(), safeGetMaxTextureSize());
	}

	private static String safeGetString(int name) {
		try {
			String value = GL11.glGetString(name);
			return value == null ? "unknown" : value;
		} catch (Throwable t) {
			return "unknown";
		}
	}

	/**
	 * The primary monitor's refresh rate.
	 *
	 * <p>GLFW is reached reflectively: newer Minecraft development jars do not expose
	 * {@code org.lwjgl.glfw} on the compile classpath at all (26.3 is one such release),
	 * while the classes are present at runtime in every launcher Beryllium supports. A direct
	 * reference would stop the mod from building there for the sake of one optional number;
	 * a lookup that fails simply reports -1, which the capability classifier already treats as
	 * "unknown".
	 */
	private static int safeGetRefreshRate() {
		try {
			Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
			Object monitor = glfw.getMethod("glfwGetPrimaryMonitor").invoke(null);
			if (!(monitor instanceof Long handle) || handle == 0L) {
				return -1;
			}
			Object mode = glfw.getMethod("glfwGetVideoMode", long.class).invoke(null, handle);
			if (mode == null) {
				return -1;
			}
			Object rate = mode.getClass().getMethod("refreshRate").invoke(mode);
			return rate instanceof Number number ? number.intValue() : -1;
		} catch (Throwable t) {
			return -1;
		}
	}

	private static int safeGetMaxTextureSize() {
		try {
			return GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
		} catch (Throwable t) {
			return -1;
		}
	}
}
