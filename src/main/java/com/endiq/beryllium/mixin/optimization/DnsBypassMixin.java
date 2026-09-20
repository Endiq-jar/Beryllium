package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.network.DnsBypass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bypasses reverse DNS check by resolving domain directly to IP.
 */
@Mixin(targets = {"net.minecraft.client.multiplayer.resolver.ServerAddress", "net.minecraft.client.multiplayer.ServerAddress", "java.net.InetAddress"})
public abstract class DnsBypassMixin {
    @Inject(method = {"parseString", "parseAddress", "getByName"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$directIp(CallbackInfoReturnable<?> cir) {
        try {
            if (!DnsBypass.enabled()) return;
            // We let vanilla proceed but with direct IP hint; actual bypass is in network layer via DnsBypass.resolve
        } catch (Throwable t) {}
    }
}
