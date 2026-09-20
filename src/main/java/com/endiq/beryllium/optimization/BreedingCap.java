package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import java.util.List;

/** Breeding caps: limit breeding within radius. */
public final class BreedingCap {
    private BreedingCap(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.breedingCap || c.breedingCaps);
    }
    public static boolean shouldDeny(Level level, BlockPos pos, String entityType) {
        if (!enabled() || level == null || pos == null) return false;
        BerylliumConfig c = Beryllium.config();
        int radius = c == null ? 32 : c.breedingCapRadius;
        int max = c == null ? 32 : c.breedingCapCount;
        try {
            List<? extends Entity> nearby = level.getEntitiesOfClass(Entity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(radius),
                e -> e.getType().toString().contains(entityType == null ? "" : entityType));
            return nearby.size() >= max;
        } catch (Throwable t) { return false; }
    }
}
