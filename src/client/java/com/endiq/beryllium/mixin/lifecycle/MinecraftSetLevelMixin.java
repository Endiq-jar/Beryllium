package com.endiq.beryllium.mixin.lifecycle;

import com.endiq.beryllium.ClientFrameHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires whenever the client's level instance changes — joining a world, changing
 * dimensions, or leaving to the menu — which is the point at which Beryllium's per-world
 * state (queued chunk rebuilds, the visibility memo) goes stale.
 *
 * <p>Replaces the modding-API world-change event so the same source builds for every
 * supported release.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSetLevelMixin {

	@Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("RETURN"), require = 0)
	private void beryllium$onWorldChanged(ClientLevel level, CallbackInfo ci) {
		ClientFrameHooks.onWorldChanged();
	}
}
