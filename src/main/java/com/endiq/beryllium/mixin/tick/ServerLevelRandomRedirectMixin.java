package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.AsyncRandomTicks;
import com.endiq.beryllium.tick.DeferredWorldActions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives a worker thread its own random source while it performs random ticks.
 *
 * <p>Vanilla draws random-tick positions from a single shared {@code Level#random}. Two
 * threads pulling from one {@code RandomSource} do not crash — they silently return
 * corrupted values, and a corrupted bound can point at a position outside the chunk being
 * ticked, which is how an "async" optimisation ends up loading chunks off-thread.
 *
 * <p>While a Beryllium worker is inside {@code ServerLevel#tickChunk}, reads of that field
 * are served from a per-thread source instead. On the server thread nothing changes at
 * all: the redirect only fires when deferral is active (that is, when Beryllium itself put
 * the work on a worker), so a vanilla-shaped random-tick sequence is preserved for every
 * chunk that is not being run async.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRandomRedirectMixin {

	@Redirect(
		method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/world/level/Level;random:Lnet/minecraft/util/RandomSource;",
			opcode = Opcodes.GETFIELD
		),
		require = 0
	)
	private RandomSource beryllium$workerRandom(ServerLevel level) {
		if (DeferredWorldActions.isDeferring()) {
			return AsyncRandomTicks.threadRandom();
		}
		return level.getRandom();
	}
}
