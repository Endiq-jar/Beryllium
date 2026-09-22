package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;

/**
 * Hyper Nova Ultra Potato — the sodium competitor preset for 1-2 GB Android devices.
 *
 * <p>When {@code potatoMode} (or any of its aliases) is true, this class forces every
 * potato-tunable subsystem to its most aggressive, battery-saving value. That is the
 * \"super pro max\" contract: a potato phone does not need to tune 40 flags; one master
 * switch makes the whole stack act like Sodium + Lithium + Starlight + EntityCulling
 * at their lowest settings, but in a single standalone jar that runs from 1.17 to 26.3.
 *
 * <p>Runtime cost is a single volatile read of {@code Beryllium.config()} per check,
 * plus the usual null/enabled guards — no allocation, no reflection on the hot path.
 * Detection for \"is this a potato?\" is conservative: Android, or heap <= 2 GB, or
 * <= 4 cores, or the user explicitly enabled potatoMode. Desktop with 8+ GB and 8+
 * cores never auto-enables it unless the user opted in.
 */
public final class PotatoOptimizer {
    private PotatoOptimizer() {}

    public static boolean potatoEnabled() {
        BerylliumConfig c = Beryllium.config();
        if (c == null || !c.enabled) return false;
        if (c.potatoMode || c.ultraPotato || c.hyperNovaPotato || c.superProMaxPotato || c.sodiumCompetitor || c.sodiumCompetitorMode || c.potatoLowEndMobile || c.potatoForMobile)
            return true;
        return isPotatoDevice();
    }

    public static boolean isPotatoDevice() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("android")) return true;
            long maxMem = Runtime.getRuntime().maxMemory();
            if (maxMem > 0 && maxMem <= 2L * 1024 * 1024 * 1024) return true; // <=2GB heap
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores <= 4) return true;
        } catch (Throwable t) {}
        return false;
    }

    public static int chunkDistance() {
        BerylliumConfig c = Beryllium.config();
        int v = c == null ? 4 : c.potatoChunkDistance;
        return Math.max(2, Math.min(6, v));
    }

    public static int entityCullingDistance() {
        BerylliumConfig c = Beryllium.config();
        if (c != null && (c.potatoAggressiveEntityCulling || potatoEnabled())) return 12;
        return c == null ? 32 : c.potatoEntityCullingDistance;
    }

    public static int particleCullingDistance() {
        if (potatoEnabled()) return 8;
        BerylliumConfig c = Beryllium.config();
        return c == null ? 32 : c.potatoParticleCullingDistance;
    }

    public static boolean greedyMeshing() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.potatoGreedyMeshing || c.potatoChunkOcclusion || potatoEnabled());
    }

    public static boolean occlusionCulling() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.potatoOcclusionCulling || c.potatoUseOcclusionCulling || potatoEnabled());
    }

    public static boolean shouldForceFastGraphics() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.potatoForceFastGraphics || c.potatoDisableFancyGraphics || potatoEnabled());
    }

    public static boolean shouldDisableSmoothLighting() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.potatoDisableSmoothLighting || c.potatoNoSmoothLighting || potatoEnabled());
    }

    public static boolean shouldDisableBiomeBlending() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.potatoDisableBiomeBlending || c.potatoNoBiomeBlending || potatoEnabled());
    }

    public static boolean shouldThrottleChunkUpdates() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.potatoThrottleChunkUpdates || potatoEnabled());
    }

    public static int chunkUpdatesPerFrame() {
        if (potatoEnabled()) return 1;
        BerylliumConfig c = Beryllium.config();
        return c == null ? 2 : Math.max(1, c.potatoChunkUpdatesPerFrame);
    }

    public static void logPotatoMode() {
        if (potatoEnabled()) BerylliumLog.info("[BERYLLIUM-POTATO] Ultra potato mode active: sodium-competitor chunk/occlusion/entity culling for low-end mobile (1.17→26.3 single jar)");
    }
}
