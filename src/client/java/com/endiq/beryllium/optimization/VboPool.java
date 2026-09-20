package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * VBO/EBO pool with DSA. Reuses buffers of config-defined size.
 */
public final class VboPool {
    private static final ArrayBlockingQueue<Integer> pool = new ArrayBlockingQueue<>(64);
    private VboPool(){}
    public static boolean enabled() {
        return Beryllium.config() != null && Beryllium.config().enabled && Beryllium.config().vboPool;
    }
    public static Integer acquire() { return enabled() ? pool.poll() : null; }
    public static void release(int id) { if (enabled()) pool.offer(id); }
}
