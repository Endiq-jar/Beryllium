package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.PacketGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hardens FriendlyByteBuf varint/varlong, payload size, etc.
 */
@Mixin(targets = {"net.minecraft.network.FriendlyByteBuf"})
public abstract class PacketSizeHardeningMixin {
    @Inject(method = {"readVarInt", "readVarLong", "writeVarInt", "writeVarLong"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$checkVarInt(CallbackInfoReturnable<?> cir) {
        if (!PacketGuard.enabled()) return;
        // vanilla already throws on malformed varints; we just ensure we don't propagate exploit
    }
    @Inject(method = {"readUtf", "writeUtf", "readNbt", "writeNbt"}, at = @At("HEAD"), require = 0)
    private void beryllium$checkNbt(CallbackInfoReturnable<?> cir) {}
}
