package com.endiq.beryllium.device;

public record GpuInfo(
	String vendor,
	String renderer,
	String glVersion,
	String shadingLanguageVersion,
	String graphicsApi,
	int displayRefreshRateHz,
	int maxTextureSize
) {
}
