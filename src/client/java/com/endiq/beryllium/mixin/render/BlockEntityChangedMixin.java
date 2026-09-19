package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.render.BlockEntityMeshCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the mesh cache when a block entity has changed its mind about what it looks like.
 *
 * <p>Sign text being edited, a banner being re-patterned, a bed being occupied: all of these
 * mark the block entity as changed, and all of them have to throw away the cached render state
 * that {@link BlockEntityMeshingMixin} would otherwise keep serving. This is the hook that makes
 * that cache safe.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityChangedMixin {
	@Inject(method = "setChanged", at = @At("HEAD"), require = 0)
	private void beryllium$forgetCachedState(CallbackInfo ci) {
		try {
			BlockPos pos = ((BlockEntity) (Object) this).getBlockPos();
			if (pos != null) {
				BlockEntityMeshCache.invalidate(pos);
			}
		} catch (Throwable t) {
			// Any doubt invalidates everything, which is the conservative direction.
			BlockEntityMeshCache.invalidateAll();
		}
	}
}
