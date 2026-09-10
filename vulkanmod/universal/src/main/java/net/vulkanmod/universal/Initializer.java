package net.vulkanmod.universal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Universal VulkanMod entry point.
 *
 * <p>The universal jar contains one complete, independently compiled VulkanMod
 * code set per supported Minecraft version, each relocated into its own
 * {@code net.vulkanmod.mc<version>} package.  This class is the single
 * client entry point of the jar: it reads the version of the game that is
 * actually running and delegates to the matching code set.  On a version
 * without a compiled code set - and on the code sets that do not match -
 * every per-version mixin configuration has had its targets removed by its
 * {@code MixinPlugin} (see the per-version trees), so the jar degrades to a
 * fully inert, launch-safe no-op on any Minecraft version.
 *
 * <p>This class deliberately references no Minecraft classes at all; the
 * per-version code sets are only ever loaded through the version-dispatch
 * map below, which is what keeps foreign versions safe.
 */
public class Initializer implements ClientModInitializer {

	public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

	/** Exact Minecraft version to the entry point class of its compiled code set. */
	private static final Map<String, String> VERSIONS = new LinkedHashMap<>();

	static {
		VERSIONS.put("1.20.4", "net.vulkanmod.mc1204.Initializer");
		VERSIONS.put("1.21", "net.vulkanmod.mc121.Initializer");
		VERSIONS.put("1.21.1", "net.vulkanmod.mc1211.Initializer");
		VERSIONS.put("1.21.3", "net.vulkanmod.mc1213.Initializer");
		VERSIONS.put("1.21.4", "net.vulkanmod.mc1214.Initializer");
		VERSIONS.put("1.21.10", "net.vulkanmod.mc12110.Initializer");
		VERSIONS.put("1.21.11", "net.vulkanmod.mc12111.Initializer");
	}

	@Override
	public void onInitializeClient() {
		String mc = currentMcVersion();
		String target = mc == null ? null : VERSIONS.get(mc);
		if (target == null) {
			LOGGER.warn("[VulkanMod] Universal build: Minecraft {} has no compiled code set in this jar "
					+ "(supported: {}); staying inert, no Vulkan renderer will be installed.", mc, VERSIONS.keySet());
			return;
		}
		try {
			Class<?> entry = Class.forName(target);
			ClientModInitializer delegate = (ClientModInitializer) entry.getDeclaredConstructor().newInstance();
			delegate.onInitializeClient();
		} catch (Throwable t) {
			LOGGER.error("[VulkanMod] Universal build: failed to start the " + mc + " code set", t);
		}
	}

	/** The running Minecraft version, or {@code null} when it cannot be read. */
	public static String currentMcVersion() {
		try {
			ModContainer minecraft = FabricLoader.getInstance().getModContainer("minecraft").orElse(null);
			return minecraft == null ? null : minecraft.getMetadata().getVersion().toString();
		} catch (Throwable t) {
			return null;
		}
	}
}
