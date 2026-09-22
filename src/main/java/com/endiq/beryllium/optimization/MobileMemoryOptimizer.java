package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocation-free hot paths for 1-2 GB devices — pooled buffers, string dedup, no boxing.
 *
 * <p>Potato phones GC every few seconds when vanilla allocates per-vertex, per-tick,
 * per-entity. This class centralizes the pooling decisions that otherwise would be
 * scattered: {@code DeduplicationCache} for resource keys/locations/vertices,
 * {@code SystemPropertiesCache} for prop lookups, and the per-thread {@code VboPool}
 * / {@code BufferPool}. All of them are forced on when {@code potatoMemoryOptimizer}
 * or the master potato switch is on, even if an individual flag was mistakenly left
 * off in an old config.
 */
public final class MobileMemoryOptimizer {
    private MobileMemoryOptimizer() {}
    private static final ConcurrentHashMap<String, String> STRING_POOL = new ConcurrentHashMap<>(512);

    public static boolean poolingEnabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.potatoAllocationPooling || c.potatoPooledAllocations || c.potatoReduceAllocations || c.potatoMemoryOptimizer || c.potatoLowMemoryMode || PotatoOptimizer.potatoEnabled());
    }

    public static String dedup(String s) {
        if (!poolingEnabled() || s == null) return s;
        String existing = STRING_POOL.get(s);
        if (existing != null) return existing;
        if (STRING_POOL.size() > 4096) STRING_POOL.clear();
        STRING_POOL.putIfAbsent(s, s);
        return s;
    }

    public static boolean shouldPoolVertices() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.dedupVertices || c.potatoVertexDeduplication || poolingEnabled());
    }

    public static boolean shouldCacheProperties() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.cacheSystemProperties || c.cacheProperties || poolingEnabled());
    }

    public static int maxFps() {
        BerylliumConfig c = Beryllium.config();
        if (c != null && c.potatoFpsCap) return Math.max(15, Math.min(120, c.potatoMaxFps));
        return 60;
    }
}
