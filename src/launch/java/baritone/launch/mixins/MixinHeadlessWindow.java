/*
 * Ostinato: a headless Window is constructed, but none of its SDL calls run.
 * createWindow returns a non-zero fake handle so the 26.3 zero-handle throw does not fire.
 * Hidden and normal still create a real SDL window. Priority is above the placement mixin so
 * the hidden-flag edit never reaches GpuBackend.createWindow on this path.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.launch.headless.HeadlessDevices;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.IntBuffer;

@Mixin(value = Window.class, priority = 2000)
public abstract class MixinHeadlessWindow {

    @Shadow
    private int width;
    @Shadow
    private int height;
    @Shadow
    private int framebufferWidth;
    @Shadow
    private int framebufferHeight;

    private static boolean ostinato$off() {
        return HeadlessPolicy.skipSdl();
    }

    // Ostinato: do not ask SDL which display is primary. 0 makes getMonitor return null.
    private static final String EIGHT_ARG =
            "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V";

    @Redirect(
            method = EIGHT_ARG,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GetPrimaryDisplay()I")
    )
    private int ostinato$primaryDisplay() {
        if (ostinato$off()) {
            return 0;
        }
        return SDLVideo.SDL_GetPrimaryDisplay();
    }

    // Ostinato: the no-monitor branch reads the window position. Fill 0,0 and do not call SDL.
    @Redirect(
            method = EIGHT_ARG,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GetWindowPosition(JLjava/nio/IntBuffer;Ljava/nio/IntBuffer;)Z"
            )
    )
    private boolean ostinato$position(long handle, IntBuffer x, IntBuffer y) {
        if (ostinato$off()) {
            if (x != null) {
                x.put(0, 0);
            }
            if (y != null) {
                y.put(0, 0);
            }
            return true;
        }
        return SDLVideo.SDL_GetWindowPosition(handle, x, y);
    }

    // Ostinato: skips GpuBackend.createWindow and SDL_SetWindowMinimumSize. A zero handle would throw.
    @Inject(method = "createWindow", at = @At("HEAD"), cancellable = true)
    private void ostinato$fakeWindow(GpuBackend backend, int w, int h, String title, CallbackInfoReturnable<Long> ci) {
        if (!ostinato$off()) {
            return;
        }
        ci.setReturnValue(HeadlessDevices.windowHandle());
    }

    @Inject(method = "setWindowMaxSize", at = @At("HEAD"), cancellable = true)
    private void ostinato$max(int w, int h, CallbackInfo ci) {
        if (ostinato$off()) {
            ci.cancel();
        }
    }

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void ostinato$mode(CallbackInfo ci) {
        if (ostinato$off()) {
            ci.cancel();
        }
    }

    // Ostinato: framebuffer fields stay in sync with the Java size. SDL_GetWindowSizeInPixels is not called.
    @Inject(method = "refreshFramebufferSize", at = @At("HEAD"), cancellable = true)
    private void ostinato$refresh(CallbackInfo ci) {
        if (!ostinato$off()) {
            return;
        }
        int w = Math.max(this.width, Window.MIN_WINDOW_WIDTH);
        int h = Math.max(this.height, Window.MIN_WINDOW_HEIGHT);
        this.framebufferWidth = w;
        this.framebufferHeight = h;
        ci.cancel();
    }

    @Inject(method = "queryFramebufferSize", at = @At("HEAD"), cancellable = true)
    private void ostinato$query(CallbackInfoReturnable<Window.FramebufferSize> ci) {
        if (!ostinato$off()) {
            return;
        }
        int w = this.framebufferWidth > 0 ? this.framebufferWidth : Math.max(this.width, Window.MIN_WINDOW_WIDTH);
        int h = this.framebufferHeight > 0 ? this.framebufferHeight : Math.max(this.height, Window.MIN_WINDOW_HEIGHT);
        ci.setReturnValue(new Window.FramebufferSize(w, h));
    }

    @Inject(method = "getPixelDensity", at = @At("HEAD"), cancellable = true)
    private void ostinato$density(CallbackInfoReturnable<Float> ci) {
        if (ostinato$off()) {
            ci.setReturnValue(1.0f);
        }
    }

    @Inject(method = "findBestMonitor", at = @At("HEAD"), cancellable = true)
    private void ostinato$monitor(CallbackInfoReturnable<com.mojang.blaze3d.platform.Monitor> ci) {
        if (ostinato$off()) {
            ci.setReturnValue(null);
        }
    }

    @Inject(method = {"setIcon", "close", "setTitle", "setFullscreen", "setWindowed", "selectCursor", "updateWindowMouseGrab", "updateFullscreenIfChanged"}, at = @At("HEAD"), cancellable = true)
    private void ostinato$noSdl(CallbackInfo ci) {
        if (ostinato$off()) {
            ci.cancel();
        }
    }

    // Ostinato: crash reports and options ask for the platform string. Do not call SDL_GetPlatform.
    @Inject(method = "getPlatform", at = @At("HEAD"), cancellable = true)
    private static void ostinato$platform(CallbackInfoReturnable<String> ci) {
        if (ostinato$off()) {
            ci.setReturnValue(System.getProperty("os.name", "unknown"));
        }
    }

    // Ostinato: the options screen reads the active mode. A fixed size avoids SDL_GetWindowFullscreenMode.
    @Inject(method = "getActiveVideoMode", at = @At("HEAD"), cancellable = true)
    private void ostinato$videoMode(CallbackInfoReturnable<VideoMode> ci) {
        if (ostinato$off()) {
            ci.setReturnValue(new VideoMode(Window.MIN_WINDOW_WIDTH, Window.MIN_WINDOW_HEIGHT, 8, 8, 8, 60));
        }
    }
}
