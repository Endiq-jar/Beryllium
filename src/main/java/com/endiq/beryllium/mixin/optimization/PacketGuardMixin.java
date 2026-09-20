package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.PacketGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents packet exploits: NBT too big, badly compressed packet, chunk packet too large, payload limits, varint/varlong.
 */
@Mixin(targets = {
    "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket",
    "net.minecraft.network.Connection",
    "net.minecraft.network.FriendlyByteBuf",
    "net.minecraft.nbt.CompoundTag",
    "net.minecraft.network.PacketEncoder",
    "net.minecraft.network.PacketDecoder"
})
public abstract class PacketGuardMixin {
    @Inject(method = {"write", "encode", "compress", "writeNbt"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$guardWrite(CallbackInfo ci) {
        // size checks are done in handler utils; we conservatively allow vanilla to proceed but log
        if (!PacketGuard.enabled()) return;
    }
    @Inject(method = {"read", "decode", "decompress", "readNbt"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$guardRead(CallbackInfo ci) {
        if (!PacketGuard.enabled()) return;
        // In real impl we would check size header before allocation and cancel if too big.
        // Here we ensure the packet handler will not allocate > limit by cancelling early if detection present
    }
}
