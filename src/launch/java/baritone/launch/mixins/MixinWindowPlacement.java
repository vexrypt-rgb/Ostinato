package baritone.launch.mixins;

import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test-harness only, inert unless -Dostinato.windowPos=x,y is set: the game window is created hidden, moved to
 * (x, y) and then shown without being activated, so an automated run never appears on, or takes focus from, the
 * user's main screen.
 */
@Mixin(Window.class)
public abstract class MixinWindowPlacement {

    @Shadow @Final private long handle;

    private static int[] ostinato$pos() {
        String p = System.getProperty("ostinato.windowPos");
        if (p == null) return null;
        try {
            String[] s = p.split(",");
            return new int[]{Integer.parseInt(s[0].trim()), Integer.parseInt(s[1].trim())};
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Inject(method = "createWindow", at = @At("HEAD"), require = 0)
    private void ostinato$hints(GpuBackend backend, int w, int h, String title, CallbackInfoReturnable<Long> ci) {
        if (ostinato$pos() == null) return;
        SDLHints.SDL_SetHint("SDL_WINDOW_ACTIVATE_WHEN_SHOWN", "0");
        SDLHints.SDL_SetHint("SDL_WINDOW_ACTIVATE_WHEN_RAISED", "0");
    }

    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3, require = 0)
    private long ostinato$hidden(long flags) {
        return ostinato$pos() == null ? flags : flags | SDLVideo.SDL_WINDOW_HIDDEN;
    }

    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V", at = @At("RETURN"), require = 0)
    private void ostinato$place(CallbackInfo ci) {
        int[] p = ostinato$pos();
        if (p == null) return;
        SDLVideo.SDL_SetWindowPosition(handle, p[0], p[1]);
        SDLVideo.SDL_ShowWindow(handle);
    }
}