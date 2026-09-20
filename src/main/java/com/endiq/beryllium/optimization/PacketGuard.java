package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/**
 * Hardens against packet exploits:
 * NBT too big (2097152), badly compressed packet, payload too large (1048576/32767),
 * chunk packet too large (8388608), varint/varlong, timeouts.
 */
public final class PacketGuard {
    private PacketGuard(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.packetSizeGuard || c.nbtSizeGuard || c.preventPacketExploits || c.packetHardening);
    }
    public static boolean isNbtTooBig(int bytes) {
        if (!enabled()) return false;
        BerylliumConfig c = Beryllium.config();
        int limit = c == null ? 2097152 : c.maxNbtBytes;
        return bytes > limit;
    }
    public static boolean isPacketTooBig(int bytes) {
        if (!enabled()) return false;
        BerylliumConfig c = Beryllium.config();
        int limit = c == null ? 8388608 : c.maxPacketBytes;
        return bytes > limit;
    }
    public static boolean isPayloadTooBig(int bytes) {
        if (!enabled()) return false;
        BerylliumConfig c = Beryllium.config();
        int limit = c == null ? 1048576 : c.maxPayloadBytes;
        return bytes > limit;
    }
    public static boolean isVarIntTooBig(int value) {
        if (!enabled()) return false;
        return value < 0 || value > 2097152;
    }
    public static boolean shouldKickOnOverflow() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && c.kickOnPacketOverflow;
    }
}
