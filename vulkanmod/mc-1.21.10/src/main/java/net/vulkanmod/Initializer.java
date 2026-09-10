package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import net.vulkanmod.config.UpdateChecker;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class Initializer implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	private static String VERSION;
	public static Config CONFIG;

	@Override
	public void onInitializeClient() {
		if (!net.vulkanmod.mixin.MixinPlugin.isExpectedMinecraft()) {
			LOGGER.info("[VulkanMod] Built for Minecraft {} but {} is running; staying inert " +
					"(no Vulkan renderer, no mixins). This install targets {}.",
					net.vulkanmod.mixin.MixinPlugin.EXPECTED_MC_VERSION,
					net.vulkanmod.mixin.MixinPlugin.currentMcVersion(),
					net.vulkanmod.mixin.MixinPlugin.EXPECTED_MC_VERSION);
			return;
		}


		VERSION = FabricLoader.getInstance()
				.getModContainer("vulkanmod")
				.get()
				.getMetadata()
				.getVersion().getFriendlyString();

		LOGGER.info("== VulkanMod ==");

		Platform.init();

		var configPath = FabricLoader.getInstance()
				.getConfigDir()
				.resolve("vulkanmod_settings.json");

		CONFIG = loadConfig(configPath);

		Renderer.register(VulkanModRenderer.INSTANCE);

		UpdateChecker.checkForUpdates();
	}

	private static Config loadConfig(Path path) {
        return Config.load(path);
	}

	public static String getVersion() {
		return VERSION;
	}
}
