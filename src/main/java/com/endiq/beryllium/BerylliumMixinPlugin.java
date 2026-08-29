package com.endiq.beryllium;

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

public class BerylliumMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Beryllium");
    private static final String MIXIN_PACKAGE_ROOT = "com.endiq.beryllium.mixin.common.";

    private boolean masterEnabled = true;
    private boolean voxelShapeOptimizations = true;
    private boolean configLoadAttempted = false;

    @Override
    public void onLoad(String mixinPackage) {
        if (this.configLoadAttempted) {
            return;
        }
        this.configLoadAttempted = true;

        Path configPath = new File("./config/beryllium.json").toPath();
        try {

            configPath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("beryllium.json");
        } catch (Throwable t) {

        }

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                MixinConfigSnapshot snapshot = new Gson().fromJson(json, MixinConfigSnapshot.class);
                if (snapshot != null) {
                    this.masterEnabled = snapshot.enabled;
                    this.voxelShapeOptimizations = snapshot.voxelShapeOptimizations;
                    LOGGER.info("[BERYLLIUM] Mixin config loaded from {}: master={}, voxelShapeOptimizations={}",
                            configPath, this.masterEnabled, this.voxelShapeOptimizations);
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
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(MIXIN_PACKAGE_ROOT)) {
            return this.masterEnabled && this.voxelShapeOptimizations;
        }

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
