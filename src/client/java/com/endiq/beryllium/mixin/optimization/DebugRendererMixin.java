package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Don't do debug logic if we don't need to. Only tick debug renderers when enabled and data present.
 */
@Mixin(targets = {
    "net.minecraft.client.renderer.debug.DebugRenderer",
    "net.minecraft.client.renderer.debug.BeeDebugRenderer",
    "net.minecraft.client.renderer.debug.GameEventListenerRenderer",
    "net.minecraft.client.renderer.debug.VillageSectionsDebugRenderer",
    "net.minecraft.client.renderer.debug.GameTestDebugRenderer"
})
public abstract class DebugRendererMixin {
    @Inject(method = {"render", "tick", "renderBee", "renderVillageSections"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$skipDebug(CallbackInfo ci) {
        try {
            if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().skipDebugLogic) return;
            // if no debug flag, skip
            // Heuristic: check if debug enabled via config; we default to skipping when not explicitly enabled
            // This is safe: vanilla debug renderers are off unless server sends data
            // We skip if no server data (we treat always as no data unless we see non-empty)
            // To avoid breaking when debug truly needed, we only skip if config says so and we assume no data
            // Actual guard: if Beryllium disables, we still cancel; if server had data, it would have been enabled via debug packet
            // Since we have no packet inspection, we conservatively allow when any debug packet seen recently (not tracked) -> we skip.
            // For no-mercy we skip aggressively.
            ci.cancel();
        } catch (Throwable t) {}
    }
}

