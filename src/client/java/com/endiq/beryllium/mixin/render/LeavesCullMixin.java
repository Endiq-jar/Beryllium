package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NOT COMPILE-VERIFIED — same sandbox caveat as the other mixins.
 *
 * Vanilla leaves never hide the face shared with a neighboring leaves block: leaves
 * aren't full/opaque per vanilla's face-culling rules, so every leaf-to-leaf boundary
 * inside a tree canopy draws two overlapping faces that can never actually be seen (each
 * is directly behind the other, both textured the same way). In a dense canopy this is a
 * large, pure-overdraw cost.
 *
 * <p>Hooks {@code Block#skipRendering(BlockState, BlockState, Direction)} — the same
 * per-block-pair "is this shared face worth drawing" check vanilla itself uses for
 * matching full blocks — and additionally returns true when both sides are leaves,
 * regardless of whether they're the exact same leaves type (oak leaves next to birch
 * leaves still hides the shared face safely; the texture on each visible outward face is
 * unaffected, so this cannot create a visible hole).
 *
 * <p>{@code skipRendering} has changed shape/name across versions before settling here in
 * recent Mojang mappings — if Mixin can't resolve it, that's the first thing to check
 * against a decompile of {@code Block}/{@code BlockBehaviour} for this exact version.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesCullMixin {

	@Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$cullInternalLeavesFaces(
		BlockState state, BlockState adjacentBlockState, Direction direction, CallbackInfoReturnable<Boolean> cir
	) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.cullLeavesInternalFaces) {
			return;
		}

		if (adjacentBlockState != null && adjacentBlockState.getBlock() instanceof LeavesBlock) {
			cir.setReturnValue(true);
		}
	}
}
