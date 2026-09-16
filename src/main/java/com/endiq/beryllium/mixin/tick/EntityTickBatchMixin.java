package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.BerylliumConfigCache;
import com.endiq.beryllium.tick.EntityTickBatcher;
import com.endiq.beryllium.tick.ExperimentalCertification;
import com.endiq.beryllium.tick.ReplayGuard;
import com.endiq.beryllium.tick.VanillaBridges;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Parallel entity processing. See {@link EntityTickBatcher} for the whole model: eligible
 * entities are taken out of vanilla's {@code Level#tickEntities} loop, grouped by a 3x3
 * chunk colouring so no two groups can touch each other, ticked across the worker pool,
 * and their recorded world mutations replayed by the server thread.
 *
 * <p>The vanilla per-entity body is not reimplemented — it is invoked through
 * {@link VanillaBridges} with {@link ReplayGuard} held, so Beryllium is running literally
 * vanilla code, just on more than one core.
 *
 * <p>Every injector is {@code require = 0}: if {@code tickEntities} or
 * {@code tickNonPassenger} is named differently on a release, nothing is collected and the
 * batch never runs.
 */
@Mixin(Level.class)
public abstract class EntityTickBatchMixin {

	@Inject(method = "tickEntities()V", at = @At("HEAD"), require = 0)
	private void beryllium$beginEntityBatch(CallbackInfo ci) {
		EntityTickBatcher.instance().beginEntities();
	}

	@Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$collectEntity(Entity entity, CallbackInfo ci) {
		if (ReplayGuard.isReplaying() || entity == null) {
			return;
		}
		if (!BerylliumConfigCache.parallelEntityTicking()) {
			return;
		}

		Level level = beryllium$self();
		if (!VanillaBridges.hasTickNonPassenger(level)) {
			return;
		}

		EntityTickBatcher batcher = EntityTickBatcher.instance();
		Runnable body = () -> VanillaBridges.tickNonPassenger(level, entity);

		// First entity of the session: tick it inline (with deferral active) so Beryllium
		// can prove its safety hooks are live before ticking anything off-thread.
		if (!ExperimentalCertification.wasAttempted()) {
			batcher.calibrate(level, entity, body);
			ci.cancel();
			return;
		}

		if (!batcher.shouldIntercept()) {
			return;
		}
		if (batcher.collect(entity, body)) {
			ci.cancel();
		}
	}

	@Inject(method = "tickEntities()V", at = @At("RETURN"), require = 0)
	private void beryllium$runEntityBatch(CallbackInfo ci) {
		EntityTickBatcher.instance().finishEntities();
	}

	@Unique
	private Level beryllium$self() {
		return (Level) (Object) this;
	}
}
