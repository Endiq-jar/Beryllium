package com.endiq.beryllium.device;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

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
			long monitor = GLFW.glfwGetPrimaryMonitor();
			if (monitor == 0L) {
				return -1;
			}
			GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
			return mode == null ? -1 : mode.refreshRate();
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
