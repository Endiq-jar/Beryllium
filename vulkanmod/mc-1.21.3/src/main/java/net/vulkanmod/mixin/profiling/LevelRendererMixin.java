package net.vulkanmod.mixin.profiling;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.profiling.Profiler;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "method_62205", at = @At("HEAD"))
    private void pushProfiler(ResourceHandle resourceHandle, int i, CloudStatus cloudStatus, float f, Matrix4f matrix4f,
                              Matrix4f matrix4f2, Vec3 vec3, float g, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Clouds");
    }

    @Inject(method = "method_62205", at = @At("RETURN"))
    private void popProfiler(ResourceHandle resourceHandle, int i, CloudStatus cloudStatus, float f, Matrix4f matrix4f,
                             Matrix4f matrix4f2, Vec3 vec3, float g, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
    }

    // TODO: fix
    @Inject(method = "method_62213", at = @At(value = "HEAD"))
    private void pushProfiler3(FogParameters fogParameters, ResourceHandle resourceHandle,
                               ResourceHandle resourceHandle2, LightTexture lightTexture, Camera camera, float f,
                               CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Particles");
    }

    @Inject(method = "method_62213", at = @At(value = "RETURN"))
    private void popProfiler3(FogParameters fogParameters, ResourceHandle resourceHandle,
                              ResourceHandle resourceHandle2, LightTexture lightTexture, Camera camera, float f,
                              CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
    }

    @Inject(method = "method_62214",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                     ordinal = 0,
                     shift = At.Shift.BEFORE))
    private void profilerTerrain1(FogParameters fogParameters, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profilerFiller, Matrix4f matrix4f, Matrix4f matrix4f2,
                                  ResourceHandle resourceHandle, ResourceHandle resourceHandle2, ResourceHandle resourceHandle3, ResourceHandle resourceHandle4,
                                  boolean bl, Frustum frustum, ResourceHandle resourceHandle5, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Opaque_terrain");
    }

    @Inject(method = "method_62214",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/Camera;Lnet/minecraft/client/DeltaTracker;Ljava/util/List;)V"))
    private void profilerTerrain2(FogParameters fogParameters, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profilerFiller, Matrix4f matrix4f, Matrix4f matrix4f2,
                                  ResourceHandle resourceHandle, ResourceHandle resourceHandle2, ResourceHandle resourceHandle3, ResourceHandle resourceHandle4,
                                  boolean bl, Frustum frustum, ResourceHandle resourceHandle5, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("entities");
    }

    @Inject(method = "method_62214",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                     ordinal = 3,
                     shift = At.Shift.BEFORE))
    private void profilerTerrain3(FogParameters fogParameters, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profilerFiller, Matrix4f matrix4f, Matrix4f matrix4f2,
                                    ResourceHandle resourceHandle, ResourceHandle resourceHandle2, ResourceHandle resourceHandle3, ResourceHandle resourceHandle4,
                                    boolean bl, Frustum frustum, ResourceHandle resourceHandle5, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("Translucent_terrain");
    }

    @Inject(method = "method_62214",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                     ordinal = 4,
                     shift = At.Shift.AFTER))
    private void profilerTerrain4(FogParameters fogParameters, DeltaTracker deltaTracker, Camera camera, ProfilerFiller profilerFiller, Matrix4f matrix4f, Matrix4f matrix4f2,
                                    ResourceHandle resourceHandle, ResourceHandle resourceHandle2, ResourceHandle resourceHandle3, ResourceHandle resourceHandle4,
                                    boolean bl, Frustum frustum, ResourceHandle resourceHandle5, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
    }


}
