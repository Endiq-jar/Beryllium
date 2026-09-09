package com.endiq.beryllium;

import com.endiq.beryllium.platform.LauncherEnvironment;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Decides, at class-load (mixin application) time, whether Beryllium's common-side
 * optimizations should be applied.
 *
 * <p>Mixins cannot be toggled at runtime once applied, so this plugin reads
 * {@code config/beryllium.json} directly when the mixin config is loaded — which happens
 * before the game's own classes are transformed, i.e. before Beryllium's normal mod
 * initialization runs. If the config file cannot be read for any reason, the safe default
 * (apply the optimizations) is used, since the optimizations are vanilla-compatible and
 * have only a performance impact.
 *
 * <p>Only the mixins under {@code com.endiq.beryllium.mixin.common} are governed by this
 * plugin; the client rendering mixins live in a separate mixin config and gate themselves
 * from the live config at injection time.
 */
public class BerylliumMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beryllium");
    private static final String MIXIN_PACKAGE_ROOT = "com.endiq.beryllium.mixin.common.";

    private boolean masterEnabled = true;
    private boolean voxelShapeOptimizations = true;
    private boolean androidSafeMode = true;
    private boolean configLoadAttempted = false;
    private boolean androidSafetyLogged = false;

    @Override
    public void onLoad(String mixinPackage) {
        if (this.configLoadAttempted) {
            return;
        }
        this.configLoadAttempted = true;

        Path configPath = new File("./config/beryllium.json").toPath();
        try {
            // On Fabric the loader is already up by the time mixins are applied; prefer
            // its config dir (identical to ./config in practice, but authoritative).
            configPath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("beryllium.json");
        } catch (Throwable t) {
            // Not on Fabric, or loader not ready yet — fall back to the game directory.
        }

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                MixinConfigSnapshot snapshot = new Gson().fromJson(json, MixinConfigSnapshot.class);
                if (snapshot != null) {
                    this.masterEnabled = snapshot.enabled;
                    this.voxelShapeOptimizations = snapshot.voxelShapeOptimizations;
                    this.androidSafeMode = snapshot.androidSafeMode;
                    LOGGER.info("[BERYLLIUM] Mixin config loaded from {}: master={}, voxelShapeOptimizations={}, androidSafeMode={}",
                            configPath, this.masterEnabled, this.voxelShapeOptimizations, this.androidSafeMode);
                    return;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("[BERYLLIUM] Could not read beryllium.json for mixin gating ({}); applying default behavior.", e.getMessage());
            }
        } else {
            LOGGER.debug("[BERYLLIUM] No beryllium.json found at mixin load time; applying default behavior.");
        }
    }

    private static class MixinConfigSnapshot {
        public boolean enabled = true;
        public boolean voxelShapeOptimizations = true;
        public boolean androidSafeMode = true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("com.endiq.beryllium.mixin.")
                && this.androidSafeMode
                && LauncherEnvironment.detect().isAndroidJavaLauncher()) {
            // This runs before a Minecraft window exists. Do not let a renderer hook
            // force GLFW/OpenGL linkage or apply against a launcher-transformed class
            // before the game can reach its title screen. Users who have validated a
            // launcher/renderer combination can explicitly set androidSafeMode=false.
            if (!this.androidSafetyLogged) {
                this.androidSafetyLogged = true;
                LOGGER.warn("[BERYLLIUM] Android launcher detected during mixin setup; "
                        + "androidSafeMode is suppressing renderer/shape mixins for this session.");
            }
            return false;
        }

        if (mixinClassName.startsWith(MIXIN_PACKAGE_ROOT)) {
            return this.masterEnabled && this.voxelShapeOptimizations;
        }

        // The plugin is also used by the 1.21.4 client mixin config. Never block a
        // mixin we do not own so other configs retain their normal behavior.
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
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
