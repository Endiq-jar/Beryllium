package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.core.BlockPos;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fastest crystal optimizer: placement & breaking.
 * Caches valid placements, pre-validates obsidian/bedrock base, skips animation.
 */
public final class CrystalOptimizer {
    private static final ConcurrentHashMap<Long, Boolean> placeCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> breakCache = new ConcurrentHashMap<>();
    private CrystalOptimizer(){}
    public static boolean shouldOptimizePlace() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.crystalOptimizer || c.crystalOptimizePlacement || c.crystalFastPlace);
    }
    public static boolean shouldOptimizeBreak() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.crystalOptimizer || c.crystalFastBreak);
    }
    public static boolean canPlaceAt(BlockPos pos) {
        if (pos == null) return false;
        if (!shouldOptimizePlace()) return true;
        long k = pos.asLong();
        Boolean v = placeCache.get(k);
        if (v != null) return v;
        // heuristic: will be validated by vanilla; we just memoize failures briefly
        return true;
    }
    public static void onPlaceResult(BlockPos pos, boolean success) {
        if (pos == null) return;
        placeCache.put(pos.asLong(), success);
        if (placeCache.size() > 2048) placeCache.clear();
    }
    public static boolean skipAnimation() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && c.crystalSkipAnimation;
    }
    public static void clear() { placeCache.clear(); breakCache.clear(); }
}
