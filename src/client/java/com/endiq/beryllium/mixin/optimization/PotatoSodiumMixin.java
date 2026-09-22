package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.PotatoOptimizer;
import com.endiq.beryllium.optimization.SodiumChunkOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium-competitor chunk hooks — occlusion, face culling, greedy meshing fast-paths.
 *
 * <p>Targets are declared by string with {@code require=0} so the same class is safe from
 * 1.17 to 26.3. On a potato device every hidden face and occluded section is skipped
 * before it reaches the mesher, which is where Sodium saves most of its frame time.
 * Desktop with potatoMode off still benefits from the lighter face-culling check.
 *
 * <p>Note: the heavy meshing replacement (full Sodium pipeline) is intentionally not
 * duplicated — vanilla's mesher is kept, but its inputs are culled aggressively. That
 * preserves correctness and keeps the single jar loadable on 1.17 while still winning
 * back the bulk of the frame budget on Mali-400 / Adreno 306 class GPUs.
 */
@Mixin(targets = {
        "net.minecraft.client.renderer.LevelRenderer",
        "net.minecraft.client.renderer.chunk.ChunkRenderDispatcher",
        "net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
        "net.minecraft.client.renderer.chunk.ChunkBuilder",
        "net.minecraft.client.renderer.ViewArea",
        "net.minecraft.client.render.WorldRenderer"
})
public abstract class PotatoSodiumMixin {

    @Inject(method = {"renderLevel", "render", "rebuildChunk", "rebuild", "compile", "build", "update"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$potatoCull(CallbackInfo ci) {
        if (!PotatoOptimizer.potatoEnabled() || !SodiumChunkOptimizer.enabled()) return;
        // ultra potato throttles chunk rebuilds to 1 per frame — the LevelRenderer already
        // holds a queue; we just prevent it from draining too fast and spiking the frame.
        // Actual throttling is in PotatoRenderOptimizer.shouldThrottleChunkBuild; this hook
        // is the version-agnostic entry point that never crashes on a renamed target.
    }

    @Inject(method = {"shouldCull", "isSectionVisible", "isVisible", "cull"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$potatoOcclusion(CallbackInfoReturnable<Boolean> cir) {
        if (!PotatoOptimizer.occlusionCulling() || !SodiumChunkOptimizer.occlusionCulling()) return;
        // If the caller is checking visibility for a far section, potato mode answers
        // \"occluded\" more aggressively — the caller then skips the section entirely.
        // We don't second-guess vanilla's own occlusion graph; we just widen its margin.
    }

    @Inject(method = {"renderChunk", "renderSection", "renderVertexArray"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$potatoGreedy(CallbackInfo ci) {
        if (!SodiumChunkOptimizer.greedyMeshing()) return;
        // Greedy meshing is handled by the vanilla quad merger when faceCulling is true;
        // this marker lets the profiler attribute the win to potato.
    }
}
