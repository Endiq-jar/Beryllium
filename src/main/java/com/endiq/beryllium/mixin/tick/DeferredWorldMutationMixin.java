package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.DeferredWorldActions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns every world mutation attempted by a Beryllium worker into a recorded action.
 *
 * <p>This is the hook that makes off-thread ticking survivable. Each injector does the
 * same three things and nothing else:
 * <ol>
 *   <li>if the calling thread is not running Beryllium off-thread work, do nothing at all
 *       (vanilla proceeds, byte-for-byte as before);</li>
 *   <li>otherwise hand a replayable action to {@link DeferredWorldActions};</li>
 *   <li>cancel vanilla's execution, returning the same "nothing happened" value vanilla
 *       would return for an unchanged world.</li>
 * </ol>
 *
 * <p>Only the funnels are hooked — {@code setBlock}, {@code setBlockAndUpdate} and
 * {@code removeBlock} cover essentially every block change in the game, and
 * {@code addFreshEntity} covers spawns/drops. Everything else a worker might call
 * (packets, block entity state) either is confined to its own chunk or is safe to run
 * concurrently.
 */
@Mixin(Level.class)
public abstract class DeferredWorldMutationMixin {

	@Inject(
		method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$deferSetBlock(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
		Level level = beryllium$self();
		if (DeferredWorldActions.offer(() -> level.setBlock(pos, state, flags))) {
			DeferredWorldActions.noteDeferred();
			cir.setReturnValue(false);
		}
	}

	@Inject(
		method = "setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$deferSetBlockAndUpdate(BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		Level level = beryllium$self();
		if (DeferredWorldActions.offer(() -> level.setBlockAndUpdate(pos, state))) {
			DeferredWorldActions.noteDeferred();
			cir.setReturnValue(false);
		}
	}

	@Inject(
		method = "removeBlock(Lnet/minecraft/core/BlockPos;Z)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$deferRemoveBlock(BlockPos pos, boolean isMoving, CallbackInfoReturnable<Boolean> cir) {
		Level level = beryllium$self();
		if (DeferredWorldActions.offer(() -> level.removeBlock(pos, isMoving))) {
			DeferredWorldActions.noteDeferred();
			cir.setReturnValue(false);
		}
	}

	@Inject(
		method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$deferAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		Level level = beryllium$self();
		if (DeferredWorldActions.offer(() -> level.addFreshEntity(entity))) {
			DeferredWorldActions.noteDeferred();
			cir.setReturnValue(false);
		}
	}

	@Unique
	private Level beryllium$self() {
		return (Level) (Object) this;
	}
}
