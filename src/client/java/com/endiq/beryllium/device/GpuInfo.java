package com.endiq.beryllium.device;

/**
 * @param graphicsApi "OpenGL ES" or "OpenGL", inferred from the GL_VERSION string.
 * @param displayRefreshRateHz -1 if the primary monitor/video mode couldn't be read.
 */
public record GpuInfo(
	String vendor,
	String renderer,
	String glVersion,
	String shadingLanguageVersion,
	String graphicsApi,
	int displayRefreshRateHz
) {
}
