package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import java.util.concurrent.ConcurrentHashMap;

/** Caches System.getProperty calls. */
public final class SystemPropertiesCache {
    private static final ConcurrentHashMap<String,String> cache = new ConcurrentHashMap<>();
    private SystemPropertiesCache(){}
    public static String get(String key) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().cacheSystemProperties) return System.getProperty(key);
        return cache.computeIfAbsent(key, k -> System.getProperty(k));
    }
    public static void invalidate() { cache.clear(); }
}
