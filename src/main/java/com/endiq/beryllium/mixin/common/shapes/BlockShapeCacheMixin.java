/*
 * Ported from Lithium (gegy1000, MIT License) — see THIRD_PARTY.md.
 */
package com.endiq.beryllium.mixin.common.shapes;

import com.endiq.beryllium.engine.voxelshape.Object2BooleanCacheTable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Block.class)
public abstract class BlockShapeCacheMixin {

	/**
	 * Phase 13 — application marker. This mixin replaces vanilla methods with
	 * {@code @Overwrite}, so there is no handler method for the runtime hook report to
	 * find; a merged {@code @Unique} field is the reliable, behaviour-free evidence
	 * that the mixin was actually applied to this Minecraft build. Never read by the
	 * game itself.
	 */
	@org.spongepowered.asm.mixin.Unique
	private static final boolean beryllium$hookMarker = true;
    private static final Object2BooleanCacheTable<VoxelShape> FULL_CUBE_CACHE = new Object2BooleanCacheTable<>(
            512,
            shape -> !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME)
    );

    /**
     * @reason Use a faster cache implementation
     * @author gegy1000 (upstream); ported for Beryllium
     */
    @Overwrite
    public static boolean isShapeFullBlock(VoxelShape shape) {
        return FULL_CUBE_CACHE.get(shape);
    }
}
