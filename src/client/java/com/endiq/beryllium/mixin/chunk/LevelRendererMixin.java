package com.endiq.beryllium.mixin.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.chunk.SectionPacking;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 4 — the *public entry-point* dirty-mark interception of the chunk rebuild
 * prioritization queue. Verified against a 1.21.4 Mojang-mapped decompile: the
 * 1.21.2 renderer refactor left two overloads on {@code LevelRenderer} —
 * {@code public setSectionDirty(int, int, int)} and {@code private
 * setSectionDirty(int, int, int, boolean)} — and both funnel into
 * {@code ViewArea.setDirty}, where {@link ViewAreaMixin} intercepts the same event
 * one level down. Block changes ({@code blockChanged -> setBlockDirty}),
 * {@code setBlocksDirty} and {@code setSectionDirtyWithNeighbors} all surface here,
 * so this (together with {@link ViewAreaMixin}) captures every dynamic rebuild
 * trigger and reorders it via {@link ChunkRebuildManager}. Requests are packed into
 * section keys with {@link SectionPacking} (the {@code SectionPos.asLong} bit
 * layout).
 *
 * <p>{@code setSectionDirty} returns void, so cancellation cannot leave a caller
 * holding a stale return value. (A {@code setSectionDirty(long, boolean)} overload
 * does not exist in 1.21.4 — it is a pre-1.21.2 shape — so it is not targeted.)
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void beryllium$queuePrioritizedSectionDirtyInt(
		int sectionX, int sectionY, int sectionZ, boolean important, CallbackInfo ci
	) {
		beryllium$queue(SectionPacking.pack(sectionX, sectionY, sectionZ), important, ci);
	}

	// Public 3-int variant (no urgency flag): called by setBlocksDirty and
	// setSectionDirtyWithNeighbors; "important" is unknown there, so treat as
	// non-urgent.
	@Inject(method = "setSectionDirty(III)V", at = @At("HEAD"), cancellable = true)
	private void beryllium$queuePrioritizedSectionDirtyLegacy(
		int sectionX, int sectionY, int sectionZ, CallbackInfo ci
	) {
		beryllium$queue(SectionPacking.pack(sectionX, sectionY, sectionZ), false, ci);
	}

	@Unique
	private void beryllium$queue(long sectionPos, boolean important, CallbackInfo ci) {
		ChunkRebuildManager manager = ChunkRebuildManager.instance();
		if (manager == null || manager.isBypassing()) {
			return;
		}
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.chunkRebuildPrioritization) {
			return;
		}
		if (Beryllium.isChunkOptimizationDeferredToOtherMod()) {
			return;
		}

		// This mixin is the guaranteed interception point on 1.21.4 (the
		// ViewAreaMixin target only fires for direct viewArea.setDirty calls), so the
		// re-trigger reference is captured here — see ChunkRebuildManager. Without
		// this, the queue would fill up and never drain (the section rebuilds would
		// stay cancelled), leaving chunk sections permanently stale after edits.
		manager.setRendererRef((Object) this);

		if (manager.enqueue(sectionPos, important)) {
			ci.cancel();
		}
	}
}
