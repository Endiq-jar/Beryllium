package net.vulkanmod.mixin;

import java.util.List;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin configuration plugin for this VulkanMod build.
 *
 * <p>Every tree in this repository is compiled against exactly one Minecraft
 * version, but the same jar - or the merged universal jar, which contains
 * every version's code set at once - may be installed on any of them.  This
 * plugin makes the contract explicit at load time: if the game that is
 * actually running is not the version this code set was compiled against,
 * every target of this configuration is removed, so no mixin is applied at
 * all.  Mixin treats a removed target set as a no-op (a warning in
 * production environments), which keeps foreign-version installs safe: the
 * renderer simply stays off and the game runs on its default OpenGL path.
 */
public class MixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    /** The exact Minecraft version this code set was compiled against. */
    public static final String EXPECTED_MC_VERSION = "1.20.4";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        try {
            if (isExpectedMinecraft()) {
                return;
            }
        } catch (Throwable t) {
            // Any failure to determine the version is treated as a mismatch.
        }
        // Removing every target removes every mixin of this configuration from
        // the transformer's target map, so nothing in this code set is applied.
        myTargets.clear();
        try {
            LOGGER.warn("[VulkanMod] Minecraft {} detected, this code set targets {}; no Vulkan mixins will be applied.",
                    currentMcVersion(), EXPECTED_MC_VERSION);
        } catch (Throwable ignored) {
            // Logging must never take the loader down with it.
        }
    }

    /** True when the running game is exactly the version this code was built for. */
    public static boolean isExpectedMinecraft() {
        return EXPECTED_MC_VERSION.equals(currentMcVersion());
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

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
