
package com.endiq.beryllium.engine.voxelshape;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.AxisCycle;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CubePointRange;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

public class VoxelShapeAlignedCuboid extends VoxelShapeSimpleCube {

    static final double LARGE_EPSILON = 10 * EPSILON;

    protected final byte xyzResolution;

    public VoxelShapeAlignedCuboid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int xRes, int yRes, int zRes) {
        super(new CuboidVoxelSet(1 << xRes, 1 << yRes, 1 << zRes, minX, minY, minZ, maxX, maxY, maxZ), minX, minY, minZ, maxX, maxY, maxZ);

        if (xRes > 3 || yRes > 3 || zRes > 3 || xRes < 0 || yRes < 0 || zRes < 0) {
            throw new IllegalArgumentException("Resolution must be between 0 and 3");
        }

        this.xyzResolution = (byte) (xRes << 4 | yRes << 2 | zRes);
    }

    public VoxelShapeAlignedCuboid(DiscreteVoxelShape voxels, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, byte xyzResolution) {
        super(voxels, minX, minY, minZ, maxX, maxY, maxZ);
        this.xyzResolution = xyzResolution;
    }

    @Override
    public VoxelShape move(double x, double y, double z) {
        return new VoxelShapeAlignedCuboidOffset(this, this.shape, x, y, z);
    }

    @Override
    public double collideX(AxisCycle cycleDirection, AABB box, double maxDist) {
        if (Math.abs(maxDist) < EPSILON) {
            return 0.0D;
        }

        double penetration = this.calculatePenetration(cycleDirection, box, maxDist);

        if ((penetration != maxDist) && this.intersects(cycleDirection, box)) {
            return penetration;
        }

        return maxDist;
    }

    private double calculatePenetration(AxisCycle dir, AABB box, double maxDist) {
        switch (dir) {
            case NONE:
                return VoxelShapeAlignedCuboid.calculatePenetration(this.minX, this.maxX, this.getXSegments(), box.minX, box.maxX, maxDist);
            case FORWARD:
                return VoxelShapeAlignedCuboid.calculatePenetration(this.minZ, this.maxZ, this.getZSegments(), box.minZ, box.maxZ, maxDist);
            case BACKWARD:
                return VoxelShapeAlignedCuboid.calculatePenetration(this.minY, this.maxY, this.getYSegments(), box.minY, box.maxY, maxDist);
            default:
                throw new IllegalArgumentException();
        }
    }

    private static double calculatePenetration(double aMin, double aMax, final int segmentsPerUnit, double bMin, double bMax, double maxDist) {
        double gap;

        if (maxDist > 0.0D) {
            gap = aMin - bMax;

            if (gap >= -EPSILON) {

                return Math.min(gap, maxDist);
            } else {

                if (segmentsPerUnit == 1) {

                    return maxDist;
                }

                double wallPos = Mth.ceil((bMax - EPSILON) * segmentsPerUnit) / (double) segmentsPerUnit;

                if (wallPos < aMax - LARGE_EPSILON) {
                    return Math.min(maxDist, wallPos - bMax);
                }
                return maxDist;
            }
        } else {

            gap = aMax - bMin;

            if (gap <= EPSILON) {

                return Math.max(gap, maxDist);
            } else {

                if (segmentsPerUnit == 1) {

                    return maxDist;
                }

                double wallPos = Mth.floor((bMin + EPSILON) * segmentsPerUnit) / (double) segmentsPerUnit;

                if (wallPos > aMin + LARGE_EPSILON) {
                    return Math.max(maxDist, wallPos - bMin);
                }
                return maxDist;
            }
        }
    }

    @Override
    public DoubleList getCoords(Direction.Axis axis) {
        return switch (axis) {
            case X -> new CubePointRange(this.getXSegments());
            case Y -> new CubePointRange(this.getYSegments());
            case Z -> new CubePointRange(this.getZSegments());
        };
    }

    @Override
    protected double get(Direction.Axis axis, int index) {
        return switch (axis) {
            case X -> (double) index / (double) this.getXSegments();
            case Y -> (double) index / (double) this.getYSegments();
            case Z -> (double) index / (double) this.getZSegments();
        };
    }

    @Override
    protected int findIndex(Direction.Axis axis, double coord) {
        int i = switch (axis) {
            case X -> this.getXSegments();
            case Y -> this.getYSegments();
            case Z -> this.getZSegments();
        };
        return Mth.clamp(Mth.floor(coord * (double) i), -1, i);
    }

    protected int getXSegments() {
        return 1 << (this.xyzResolution >>> 4);
    }

    protected int getYSegments() {
        return 1 << ((this.xyzResolution >>> 2) & 3);
    }

    protected int getZSegments() {
        return 1 << (this.xyzResolution & 3);
    }
}
