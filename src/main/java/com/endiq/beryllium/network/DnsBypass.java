package com.endiq.beryllium.network;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import java.util.concurrent.ConcurrentHashMap;

/** Bypasses reverse DNS by caching domain -> IP direct. */
public final class DnsBypass {
    private static final ConcurrentHashMap<String,String> cache = new ConcurrentHashMap<>();
    private DnsBypass(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.dnsBypass || c.bypassReverseDns || c.useDirectIp);
    }
    public static String resolve(String domain) {
        if (!enabled() || domain == null) return domain;
        // If already IP, return directly
        if (domain.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || domain.contains(":")) return domain;
        String cached = cache.get(domain);
        if (cached != null) return cached;
        // In real impl would do DNS lookup; here just cache domain as-is to avoid reverse check
        cache.put(domain, domain);
        return domain;
    }
    public static void put(String domain, String ip) { if (domain != null && ip != null) cache.put(domain, ip); }
}
