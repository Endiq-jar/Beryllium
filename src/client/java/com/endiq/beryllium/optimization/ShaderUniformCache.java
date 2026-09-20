package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import java.util.concurrent.ConcurrentHashMap;

/** Caches shader uniforms, logs, programs to reduce GL calls. */
public final class ShaderUniformCache {
    private static final ConcurrentHashMap<String, Integer> uniformCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> programCache = new ConcurrentHashMap<>();
    private ShaderUniformCache(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.shaderUniformCache || c.cacheShaderUniforms);
    }
    public static Integer getUniform(String key) { return enabled() ? uniformCache.get(key) : null; }
    public static void putUniform(String key, int loc) { if (enabled()) uniformCache.put(key, loc); }
    public static void clear() { uniformCache.clear(); programCache.clear(); }
}
