package com.endiq.beryllium.mixin.chunk;

import com.endiq.beryllium.chunk.ChunkUploadPacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * Chunk upload pacing.
 *
 * <p>Compiled geometry has to be uploaded to the GPU before it can be drawn, and vanilla
 * drains that queue whenever it reaches the upload step — which, after a flight, a big
 * redstone flood or a world load, means one frame can be handed dozens of uploads at once.
 * The uploads themselves are driver calls; a burst of them is a frame spike.
 *
 * <p>The drain is a {@code while ((task = toUpload.poll()) != null) task.run();} loop, which
 * is why this is a {@code @Redirect} on the poll rather than a cancellable {@code @Inject}
 * at the head of the method. Answering {@code null} stops the loop with everything still
 * queued, so {@link ChunkUploadPacer} can let a bounded number of uploads through per frame
 * and leave the rest for the next one — true pacing, per upload.
 *
 * <p>Cancelling the method instead would have been wrong on 1.21.8 and later, where the same
 * method also drains a second queue of {@code SectionMesh} objects that have to be closed.
 * Skipping that half leaks GPU resources for as long as the upload half is deferred, so the
 * pacer distinguishes the two queues by what they hold (uploads are {@code Runnable}s) and
 * passes the cleanup queue straight through.
 *
 * <p>The owning class was renamed once inside the supported range — {@code ChunkRenderDispatcher}
 * up to 1.20.5, {@code SectionRenderDispatcher} from 1.20.6 — and both are listed by string
 * so this file compiles for every release. {@code LevelRenderer} and the 1.21.2
 * {@code ViewArea} are kept as candidates because they are the drain's callers and cost
 * nothing to target: every injector here is {@code require = 0}, so a target that does not
 * carry the method is skipped rather than guessed at. From 26.1 the drain is gone entirely —
 * compiled geometry is uploaded inline into shared uber-buffers, flushed by
 * {@code uploadGlobalGeomBuffersToGPU} / {@code uploadTerrainBuffersToGpu} in a fixed handful
 * of bulk copies rather than one call per section — so no target matches and upload pacing
 * degrades to compilation scheduling, which is the lever that still exists on those releases.
 */
@Mixin(targets = {
	"net.minecraft.client.renderer.chunk.ChunkRenderDispatcher",
	"net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
	"net.minecraft.client.renderer.LevelRenderer",
	"net.minecraft.client.renderer.ViewArea"
})
public abstract class ChunkUploadPacingMixin {

	@Redirect(
		method = "uploadAllPendingUploads()V",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/Queue;poll()Ljava/lang/Object;",
			remap = false
		),
		require = 0
	)
	private Object beryllium$pacedUploadPoll(Queue<?> queue) {
		return ChunkUploadPacer.instance().nextUpload(queue);
	}

	/**
	 * From 1.20.6 the dispatcher's {@code dispose()} uses the same drain as its teardown
	 * flush, and there every remaining upload is also what hands its buffer pack back to the
	 * pool. Pacing it would strand those at world exit, so the pacer is told to let the whole
	 * queue through for the duration of the call. (On 1.19.4 and 1.20.1 {@code dispose()}
	 * clears its buffers instead and never calls the drain; the injectors simply do nothing
	 * harmful there.)
	 */
	@Inject(method = "dispose()V", at = @At("HEAD"), require = 0)
	private void beryllium$beginUnpacedUploadFlush(CallbackInfo ci) {
		ChunkUploadPacer.instance().beginUnpacedFlush();
	}

	@Inject(method = "dispose()V", at = @At("TAIL"), require = 0)
	private void beryllium$endUnpacedUploadFlush(CallbackInfo ci) {
		ChunkUploadPacer.instance().endUnpacedFlush();
	}
}
