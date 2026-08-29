
package com.endiq.beryllium.engine.voxelshape;

import net.minecraft.world.phys.AABB;

public interface VoxelShapeCaster {

    boolean intersects(AABB box, double blockX, double blockY, double blockZ);
}
