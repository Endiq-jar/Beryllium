package com.endiq.beryllium.mixin.chunk;

import com.endiq.beryllium.chunk.ChunkUploadPacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk upload pacing.
 *
 * <p>Compiled geometry has to be uploaded to the GPU before it can be drawn, and vanilla
 * drains that queue whenever it reaches the upload step — which, after a flight, a big
 * redstone flood or a world load, means one frame can be handed dozens of uploads at once.
 * The uploads themselves are driver calls; a burst of them is a frame spike.
 *
 * <p>{@link ChunkUploadPacer} gives each frame a small allowance and defers the rest to the
 * next frames. Cancelling the upload step when the allowance is spent is safe precisely
 * because it is a queue: the uploads stay pending and simply happen one frame later.
 *
 * <p>Three targets are listed because the class that owns this step has moved twice inside
 * the supported range ({@code LevelRenderer}, then {@code SectionRenderDispatcher}, then
 * the 1.21.2+ {@code ViewArea}). They are named by string so this file compiles for every
 * release, and any target that does not exist is skipped.
 */
@Mixin(targets = {
	"net.minecraft.client.renderer.LevelRenderer",
	"net.minecraft.client.renderer.chunk.SectionRenderDispatcher",
	"net.minecraft.client.renderer.ViewArea"
})
public abstract class ChunkUploadPacingMixin {

	@Inject(method = "uploadAllPendingUploads()V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$paceChunkUploads(CallbackInfo ci) {
		if (!ChunkUploadPacer.instance().allowUpload()) {
			ci.cancel();
		}
	}
}
