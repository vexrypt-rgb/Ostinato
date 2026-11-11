/*
 * Ostinato: MonitorManager's constructor calls SDL_GetDisplays. Headless returns null, which the
 * constructor already treats as "no displays". Hidden and normal still enumerate.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.MonitorManager;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

@Mixin(MonitorManager.class)
public class MixinHeadlessMonitors {

    // Ostinato: null skips the display loop and does not call SDL_free.
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GetDisplays()Ljava/nio/IntBuffer;")
    )
    private IntBuffer ostinato$displays() {
        if (HeadlessPolicy.skipSdl()) {
            return null;
        }
        return SDLVideo.SDL_GetDisplays();
    }

    @Inject(method = "findBestMonitor", at = @At("HEAD"), cancellable = true)
    private void ostinato$best(Window window, CallbackInfoReturnable<Monitor> ci) {
        if (HeadlessPolicy.skipSdl()) {
            ci.setReturnValue(null);
        }
    }
}
