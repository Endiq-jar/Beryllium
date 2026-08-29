
package com.endiq.beryllium.mixin.common.shapes;

import com.endiq.beryllium.engine.voxelshape.VoxelShapeAlignedCuboid;
import com.endiq.beryllium.engine.voxelshape.VoxelShapeEmpty;
import com.endiq.beryllium.engine.voxelshape.VoxelShapeSimpleCube;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Shapes.class)
public abstract class SpecializedShapesMixin {
    @Mutable
    @Shadow
    @Final
    public static final VoxelShape INFINITY;

    @Mutable
    @Shadow
    @Final
    private static final VoxelShape BLOCK;

    @Mutable
    @Shadow
    @Final
    private static final VoxelShape EMPTY;

    private static final DiscreteVoxelShape FULL_CUBE_VOXELS;

    static {

        FULL_CUBE_VOXELS = new BitSetDiscreteVoxelShape(1, 1, 1);
        FULL_CUBE_VOXELS.fill(0, 0, 0);

        INFINITY = new VoxelShapeSimpleCube(FULL_CUBE_VOXELS, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

        BLOCK = new VoxelShapeSimpleCube(FULL_CUBE_VOXELS, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

        EMPTY = new VoxelShapeEmpty(new BitSetDiscreteVoxelShape(0, 0, 0));
    }

    @Overwrite
    public static VoxelShape create(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (maxX - minX < 1.0E-7D || maxY - minY < 1.0E-7D || maxZ - minZ < 1.0E-7D) {
            return EMPTY;
        }

        int xRes;
        int yRes;
        int zRes;

        if ((xRes = Shapes.findBits(minX, maxX)) < 0 ||
                (yRes = Shapes.findBits(minY, maxY)) < 0 ||
                (zRes = Shapes.findBits(minZ, maxZ)) < 0) {

            return new VoxelShapeSimpleCube(FULL_CUBE_VOXELS, minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            if (xRes == 0 && yRes == 0 && zRes == 0) {
                return BLOCK;
            }

            return new VoxelShapeAlignedCuboid(Math.round(minX * 8D) / 8D, Math.round(minY * 8D) / 8D, Math.round(minZ * 8D) / 8D,
                    Math.round(maxX * 8D) / 8D, Math.round(maxY * 8D) / 8D, Math.round(maxZ * 8D) / 8D, xRes, yRes, zRes);
        }
    }
}
