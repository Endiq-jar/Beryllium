package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Spigot/Paper Entity Activation Range port: only tick entities near players.
 */
public final class EntityActivationRange {
    private EntityActivationRange(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.entityActivationRange || c.activationRangeEnabled);
    }
    public static int rangeFor(Entity e) {
        BerylliumConfig c = Beryllium.config();
        if (c == null || !enabled() || e == null) return 48;
        String name = e.getClass().getSimpleName().toLowerCase();
        if (name.contains("villager")) return c.activationRangeVillager;
        if (name.contains("monster") || name.contains("zombie") || name.contains("skeleton") || name.contains("creeper")) return c.activationRangeMonster;
        if (name.contains("animal") || name.contains("cow") || name.contains("sheep") || name.contains("pig")) return c.activationRangeAnimal;
        if (name.contains("water") || name.contains("fish") || name.contains("squid")) return c.activationRangeWater;
        if (name.contains("flying") || name.contains("bat") || name.contains("parrot")) return c.activationRangeFlying;
        return c.activationRangeMisc;
    }
    public static boolean shouldTick(Entity e, Vec3 nearestPlayerPos) {
        if (!enabled() || e == null || nearestPlayerPos == null) return true;
        int range = rangeFor(e);
        double dx = e.getX() - nearestPlayerPos.x;
        double dy = e.getY() - nearestPlayerPos.y;
        double dz = e.getZ() - nearestPlayerPos.z;
        return dx*dx + dy*dy + dz*dz < (double)range * range;
    }
}
