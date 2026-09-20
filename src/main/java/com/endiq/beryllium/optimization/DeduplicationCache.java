package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deduplication for ResourceKey, ResourceLocation, Vertices, etc.
 */
public final class DeduplicationCache {
    private static final ConcurrentHashMap<String, String> locCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, int[]> vertexCache = new ConcurrentHashMap<>();
    private DeduplicationCache(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && c.deduplication;
    }
    public static String dedupLocation(String loc) {
        if (!enabled() || loc == null) return loc;
        BerylliumConfig c = Beryllium.config();
        if (c != null && !c.dedupResourceLocation && !c.dedupResourceKey) return loc;
        String existing = locCache.putIfAbsent(loc, loc);
        return existing != null ? existing : loc;
    }
    public static void clear() {
        if (locCache.size() > 8192) locCache.clear();
        if (vertexCache.size() > 8192) vertexCache.clear();
    }
}
