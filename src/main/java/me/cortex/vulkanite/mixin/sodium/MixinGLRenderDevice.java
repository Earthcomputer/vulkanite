package me.cortex.vulkanite.mixin.sodium;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVkBuffer;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {"me.jellysquid.mods.sodium.client.gl.device.GLRenderDevice$ImmediateCommandList"}, remap = false)
public class MixinGLRenderDevice {
    @Inject(method = "deleteBuffer", at = @At("HEAD"), cancellable = true)
    private void redirectDelete(GlBuffer buffer, CallbackInfo ci) {
        if (buffer instanceof IVkBuffer vkBuffer && vkBuffer.getBuffer() != null) {
            Vulkanite.INSTANCE.addSyncedCallback(vkBuffer.getBuffer()::free);
            ci.cancel();
        }
    }
}
