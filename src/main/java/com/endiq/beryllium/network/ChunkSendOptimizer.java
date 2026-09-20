package com.endiq.beryllium.network;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimizes chunk sending for poor connections: batch and pace.
 */
public final class ChunkSendOptimizer {
    private static final AtomicInteger queued = new AtomicInteger();
    private ChunkSendOptimizer(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.chunkSendOptimization || c.optimizeChunkSending || c.chunkSendThrottle);
    }
    public static int batchSize() {
        BerylliumConfig c = Beryllium.config();
        return c == null ? 4 : Math.max(1, c.chunkSendBatchSize);
    }
    public static boolean shouldSendNow(int pending) {
        if (!enabled()) return true;
        return pending <= batchSize();
    }
    public static void onEnqueue() { queued.incrementAndGet(); }
    public static void onSend() { queued.decrementAndGet(); }
}
