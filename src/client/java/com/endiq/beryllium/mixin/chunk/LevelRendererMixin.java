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
 * NOT COMPILE-VERIFIED — same sandbox caveat as the other mixins: targets
 * {@code LevelRenderer.setSectionDirty} by string descriptor with {@code require = 0}.
 * Both the 4-int and the long overload are targeted because the exact 1.21.4 surface is
 * not confirmed against a decompile; whichever exists applies, and if neither matches the
 * feature silently no-ops (vanilla behavior preserved).
 *
 * <p>Phase 4 — the *public entry-point* dirty-mark interception of the chunk rebuild
 * prioritization queue. {@code LevelRenderer.setSectionDirty} is where block changes and
 * {@code setSectionDirtyWithNeighbors} surface, so intercepting here (together with
 * {@link SectionRenderDispatcherMixin} at the dispatcher level, which the re-trigger uses
 * anyway) catches every dynamic rebuild trigger and reorders it via
 * {@link ChunkRebuildManager}. Requests are packed into section keys with
 * {@link SectionPacking} (the {@code SectionPos.asLong} bit layout).
 *
 * <p>{@code setSectionDirty} returns void, so cancellation cannot leave a caller holding
 * a stale return value — the safety analysis is the same as in
 * {@link SectionRenderDispatcherMixin}.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "setSectionDirty(IIIIZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$queuePrioritizedSectionDirtyInt(
		int sectionX, int sectionY, int sectionZ, boolean important, CallbackInfo ci
	) {
		beryllium$queue(SectionPacking.pack(sectionX, sectionY, sectionZ), important, ci);
	}

	@Inject(method = "setSectionDirty(JZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$queuePrioritizedSectionDirtyLong(long sectionPos, boolean important, CallbackInfo ci) {
		beryllium$queue(sectionPos, important, ci);
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

		if (manager.enqueue(sectionPos, important)) {
			ci.cancel();
		}
	}
}
