package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.hidden.HiddenWindowPolicy;
import baritone.api.run.RunMode;
import baritone.launch.hidden.HiddenClientWindow;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
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
 * Ostinato: window placement. Inert for a normal client with no {@code -Dostinato.windowPos}.
 * <p>
 * With {@code -Dostinato.windowPos=x,y} and {@code runMode} normal: the window is created hidden, moved to (x, y),
 * and shown without being activated. That path is unchanged.
 * <p>
 * With {@code -Dostinato.runMode=hidden}: the window is created hidden and stays hidden. The GPU backend still
 * creates the GL context, because the hidden flag is only OR'd into the create flags. The window is not destroyed
 * and {@code SDL_RaiseWindow} is not called. A later show goes through {@link HiddenClientWindow}.
 */
@Mixin(Window.class)
public abstract class MixinWindowPlacement {

    @Shadow @Final private long handle;

    private static int[] ostinato$pos() {
        // Ostinato: same parser as before this mode. Null when the property is absent or not a pair.
        return HiddenWindowPolicy.parsePair(System.getProperty("ostinato.windowPos"));
    }

    @Inject(method = "createWindow", at = @At("HEAD"), require = 0)
    private void ostinato$hints(GpuBackend backend, int w, int h, String title, CallbackInfoReturnable<Long> ci) {
        // Ostinato: window-create reads the JVM property only. Settings are not loaded yet.
        // Headless cancels createWindow before this runs. This return is the belt if that order changes.
        if (HeadlessPolicy.skipSdl()) return;
        if (!HiddenWindowPolicy.suppressActivate(RunMode.windowCreateMode(), ostinato$pos() != null)) return;
        HiddenClientWindow.armNoActivateHints();
    }

    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3, require = 0)
    private long ostinato$hidden(long flags) {
        // Ostinato: OR SDL_WINDOW_HIDDEN. The backend still creates the GL context for that window.
        if (!HiddenWindowPolicy.createHidden(RunMode.windowCreateMode(), ostinato$pos() != null)) {
            return flags;
        }
        return flags | SDLVideo.SDL_WINDOW_HIDDEN;
    }

    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V", at = @At("RETURN"), require = 0)
    private void ostinato$place(CallbackInfo ci) {
        // Ostinato: normal + windowPos still positions and shows. Hidden stays hidden.
        HiddenClientWindow.afterCreate(handle, RunMode.windowCreateMode(), ostinato$pos());
    }
}