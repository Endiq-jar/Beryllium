/*
 * Ported from Lithium (JellySquid et al., MIT License) — see THIRD_PARTY.md.
 */
package com.endiq.beryllium.mixin.common.shapes;

import com.endiq.beryllium.engine.voxelshape.VoxelShapeMatchesAnywhere;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Shapes.class)
public abstract class ShapeMatchFastPathMixin {
    @Inject(
            method = "joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z",
            at = @At(
                    shift = At.Shift.BEFORE,
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/VoxelShape;getCoords(Lnet/minecraft/core/Direction$Axis;)Lit/unimi/dsi/fastutil/doubles/DoubleList;",
                    ordinal = 0
            ),
            cancellable = true
    )
    private static void beryllium$cuboidMatchesAnywhere(VoxelShape shapeA, VoxelShape shapeB, BooleanOp predicate, CallbackInfoReturnable<Boolean> cir) {
        VoxelShapeMatchesAnywhere.cuboidMatchesAnywhere(shapeA, shapeB, predicate, cir);
    }
}
