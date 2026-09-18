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
 *
 * <p>The monitor refresh rate is looked up reflectively because the windowing library
 * that provides it left the classpath during the supported range (26.3 dropped GLFW); a
 * missing library costs the refresh-rate input to the tier decision, never the frame.
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

	private static int safeGetRefreshRate() {
		try {
			Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
			Object monitor = glfw.getMethod("glfwGetPrimaryMonitor").invoke(null);
			if (!(monitor instanceof Number number) || number.longValue() == 0L) {
				return -1;
			}
			Object mode = glfw.getMethod("glfwGetVideoMode", long.class).invoke(null, number.longValue());
			if (mode == null) {
				return -1;
			}
			Object rate = mode.getClass().getMethod("refreshRate").invoke(mode);
			return rate instanceof Number n ? n.intValue() : -1;
		} catch (ClassNotFoundException missing) {
			// No windowing library on this release (26.3+); the refresh rate stays unknown.
			return -1;
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
