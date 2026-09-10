/*
 * Ported from Lithium (JellySquid et al., MIT License) — see THIRD_PARTY.md.
 */
package com.endiq.beryllium.mixin.common.shapes;

import net.minecraft.world.phys.shapes.CubePointRange;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CubePointRange.class)
public abstract class CubePointRangeScaleMixin {

	/**
	 * Phase 13 — application marker. This mixin replaces vanilla methods with
	 * {@code @Overwrite}, so there is no handler method for the runtime hook report to
	 * find; a merged {@code @Unique} field is the reliable, behaviour-free evidence
	 * that the mixin was actually applied to this Minecraft build. Never read by the
	 * game itself.
	 */
	@org.spongepowered.asm.mixin.Unique
	private static final boolean beryllium$hookMarker = true;
    @Shadow
    @Final
    private int parts;

    private double scale;

    @Inject(method = "<init>(I)V", at = @At("RETURN"))
    public void beryllium$initScale(int sectionCount, CallbackInfo ci) {
        this.scale = 1.0D / this.parts;
    }

    /**
     * @author JellySquid (upstream); ported for Beryllium
     * @reason Replace division with multiplication
     */
    @Overwrite
    public double getDouble(int position) {
        return position * this.scale;
    }
}
