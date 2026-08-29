
package com.endiq.beryllium.mixin.common.shapes;

import com.endiq.beryllium.engine.voxelshape.pairs.BerylliumDoublePairList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.phys.shapes.IndexMerger;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Shapes.class)
public abstract class ShapeMergeFastPathMixin {

    @Inject(
            method = "createIndexMerger(ILit/unimi/dsi/fastutil/doubles/DoubleList;Lit/unimi/dsi/fastutil/doubles/DoubleList;ZZ)Lnet/minecraft/world/phys/shapes/IndexMerger;",
            at = @At(
                    shift = At.Shift.BEFORE,
                    value = "NEW",
                    target = "(Lit/unimi/dsi/fastutil/doubles/DoubleList;Lit/unimi/dsi/fastutil/doubles/DoubleList;ZZ)Lnet/minecraft/world/phys/shapes/IndirectMerger;"
            ),
            cancellable = true
    )
    private static void beryllium$injectCustomListPair(int size, DoubleList a, DoubleList b, boolean flag1, boolean flag2, CallbackInfoReturnable<IndexMerger> cir) {
        cir.setReturnValue(new BerylliumDoublePairList(a, b, flag1, flag2));
    }
}
