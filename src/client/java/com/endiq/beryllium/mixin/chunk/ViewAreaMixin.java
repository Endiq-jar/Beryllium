package com.endiq.beryllium.mixin.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.chunk.SectionDirtyBridge;
import com.endiq.beryllium.chunk.SectionPacking;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 4 — the *dispatcher-level* dirty-mark interception of the chunk rebuild
 * prioritization queue, targeted at the real 1.21.4 pipeline.
 *
 * <p>The 1.21.2 renderer refactor removed {@code SectionRenderDispatcher#setSectionDirty}
 * entirely (verified against a 1.21.4 Mojang-mapped decompile: the class only exposes
 * {@code setLevel}/{@code setCamera}/{@code schedule}/{@code uploadAllPendingUploads}
 * and the inner {@code RenderSection} API). Every dynamic dirtiness mark now funnels
 * through {@code LevelRenderer.setSectionDirty} ({@link LevelRendererMixin}, the
 * public entry point) into {@code ViewArea#setDirty(int, int, int, boolean)} — the
 * 1.21.4 delegate that looks the section up and calls {@code RenderSection.setDirty}.
 * This mixin intercepts that delegate, so any caller that marks a section directly —
 * not just through LevelRenderer — is caught as well. The section is parked in
 * {@link ChunkRebuildManager}'s priority queue instead of being scheduled
 * immediately; the per-frame drain re-triggers it through vanilla's own
 * {@code setDirty}/{@code setSectionDirty} via {@link SectionDirtyBridge},
 * guarded by the manager's bypass flag.
 *
 * <p>Deliberately NOT intercepting {@code SectionRenderDispatcher.RenderSection#setDirty}:
 * that is the leaf mark, also called by the async compile-completion path, and
 * cancelling it would fight the rebuild pipeline itself. {@code ViewArea.setDirty}
 * returns void, so cancelling is safe — the same analysis as {@link LevelRendererMixin}.
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {

	@Inject(method = "setDirty(IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void beryllium$queuePrioritizedSectionDirty(
		int sectionX, int sectionY, int sectionZ, boolean important, CallbackInfo ci
	) {
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

		manager.setRendererRef((Object) this);
		if (manager.enqueue(SectionPacking.pack(sectionX, sectionY, sectionZ), important)) {
			ci.cancel();
		}
	}
}
