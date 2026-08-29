
package com.endiq.beryllium.engine.voxelshape;

import com.endiq.beryllium.engine.voxelshape.lists.OffsetFractionalDoubleList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.AxisCycle;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

public class VoxelShapeAlignedCuboidOffset extends VoxelShapeAlignedCuboid {

    private final double xOffset, yOffset, zOffset;

    public VoxelShapeAlignedCuboidOffset(VoxelShapeAlignedCuboid originalShape, DiscreteVoxelShape voxels, double xOffset, double yOffset, double zOffset) {
        super(voxels,
                originalShape.minX + xOffset, originalShape.minY + yOffset, originalShape.minZ + zOffset,
                originalShape.maxX + xOffset, originalShape.maxY + yOffset, originalShape.maxZ + zOffset, originalShape.xyzResolution);

        if (originalShape instanceof VoxelShapeAlignedCuboidOffset) {
            this.xOffset = ((VoxelShapeAlignedCuboidOffset) originalShape).xOffset + xOffset;
            this.yOffset = ((VoxelShapeAlignedCuboidOffset) originalShape).yOffset + yOffset;
            this.zOffset = ((VoxelShapeAlignedCuboidOffset) originalShape).zOffset + zOffset;
        } else {
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.zOffset = zOffset;
        }
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
                return VoxelShapeAlignedCuboidOffset.calculatePenetration(this.minX, this.maxX, this.getXSegments(), this.xOffset, box.minX, box.maxX, maxDist);
            case FORWARD:
                return VoxelShapeAlignedCuboidOffset.calculatePenetration(this.minZ, this.maxZ, this.getZSegments(), this.zOffset, box.minZ, box.maxZ, maxDist);
            case BACKWARD:
                return VoxelShapeAlignedCuboidOffset.calculatePenetration(this.minY, this.maxY, this.getYSegments(), this.yOffset, box.minY, box.maxY, maxDist);
            default:
                throw new IllegalArgumentException();
        }
    }

    private static double calculatePenetration(double aMin, double aMax, final int segmentsPerUnit, double shapeOffset, double bMin, double bMax, double maxDist) {
        double gap;

        if (maxDist > 0.0D) {
            gap = aMin - bMax;

            if (gap >= -EPSILON) {

                return Math.min(gap, maxDist);
            } else {

                if (segmentsPerUnit == 1) {

                    return maxDist;
                }

                int segment = Mth.ceil((bMax - LARGE_EPSILON - shapeOffset) * segmentsPerUnit);
                double wallPos = segment / (double) segmentsPerUnit + shapeOffset;
                if (wallPos < bMax - EPSILON) {
                    ++segment;
                    wallPos = segment / (double) segmentsPerUnit + shapeOffset;
                }

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

                int segment = Mth.floor((bMin + LARGE_EPSILON - shapeOffset) * segmentsPerUnit);
                double wallPos = segment / (double) segmentsPerUnit + shapeOffset;
                if (wallPos > bMin + EPSILON) {
                    --segment;
                    wallPos = segment / (double) segmentsPerUnit + shapeOffset;
                }

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
            case X -> new OffsetFractionalDoubleList(this.getXSegments(), this.xOffset);
            case Y -> new OffsetFractionalDoubleList(this.getYSegments(), this.yOffset);
            case Z -> new OffsetFractionalDoubleList(this.getZSegments(), this.zOffset);
        };
    }

    @Override
    protected double get(Direction.Axis axis, int index) {
        return switch (axis) {
            case X -> this.xOffset + (double) index / (double) this.getXSegments();
            case Y -> this.yOffset + (double) index / (double) this.getYSegments();
            case Z -> this.zOffset + (double) index / (double) this.getZSegments();
        };
    }

    @Override
    protected int findIndex(Direction.Axis axis, double coord) {
        int numSegments;
        coord = switch (axis) {
            case X -> (coord - this.xOffset) * (numSegments = this.getXSegments());
            case Y -> (coord - this.yOffset) * (numSegments = this.getYSegments());
            case Z -> (coord - this.zOffset) * (numSegments = this.getZSegments());
        };
        return Mth.clamp(Mth.floor(coord), -1, numSegments);
    }
}
