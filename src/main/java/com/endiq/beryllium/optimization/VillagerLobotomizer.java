package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * Villager lobotomization: villagers stuck in 1x1 tick less often.
 * Uses Entity to avoid version-specific Villager import issues (Villager moved packages in 26.x).
 */
public final class VillagerLobotomizer {
    private VillagerLobotomizer(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.villagerLobotomize || c.villagerLobotomization || c.villagerTickIn1x1Less);
    }
    public static boolean isLobotomized(Entity v) {
        if (!enabled() || v == null) return false;
        try {
            // check 1x1 confinement: simplified check if entity hasn't moved
            return v.getDeltaMovement().lengthSqr() < 1e-6;
        } catch (Throwable t) { return false; }
    }
    public static boolean shouldTick(Entity v, long gameTime) {
        if (!isLobotomized(v)) return true;
        BerylliumConfig c = Beryllium.config();
        int interval = c == null ? 20 : c.villagerLobotomizeTickInterval;
        return (gameTime % Math.max(1, interval)) == 0;
    }
}
