/*
 * Ostinato: when this process was created headless, do not load a graphics library or create a GPU device.
 * A stub device is returned so WindowInitFailed is not thrown. Hidden and normal call the real backend.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.launch.headless.HeadlessDevices;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLInit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinHeadlessStartup {

    // Ostinato: loadLibrary pulls in the GL or Vulkan native. Headless does not.
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuBackend;loadLibrary()V"
            )
    )
    private void ostinato$loadLibrary(GpuBackend backend) throws BackendCreationException {
        if (HeadlessPolicy.skipSdl()) {
            return;
        }
        backend.loadLibrary();
    }

    // Ostinato: createDevice is the real GL/Vulkan device. Headless returns the stub instead.
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;"
            )
    )
    private GpuDevice ostinato$createDevice(GpuBackend backend, GpuDebugOptions options) throws BackendCreationException {
        if (HeadlessPolicy.skipSdl()) {
            return HeadlessDevices.device();
        }
        return backend.createDevice(options);
    }

    // Ostinato: the default-backend path probes Vulkan before the loop. That probe loads a native. Skip it.
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;checkBackendAvailable()Lcom/mojang/renderpearl/api/device/BackendCreationException;"
            )
    )
    private BackendCreationException ostinato$vulkanProbe() {
        if (HeadlessPolicy.skipSdl()) {
            return null;
        }
        return VulkanBackend.checkBackendAvailable();
    }

    // Ostinato: close() calls SDL_Quit. The library was never initialized.
    @Redirect(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLInit;SDL_Quit()V"))
    private void ostinato$quit() {
        if (HeadlessPolicy.skipSdl()) {
            return;
        }
        SDLInit.SDL_Quit();
    }

    // Ostinato: preloadUiShader compiles the UI shader against the device. Headless has nothing to compile.
    @Inject(method = "loadCriticalShaders", at = @At("HEAD"), cancellable = true)
    private void ostinato$shaders(CallbackInfo ci) {
        if (HeadlessPolicy.skipGpuWorkNow()) {
            ci.cancel();
        }
    }
}
