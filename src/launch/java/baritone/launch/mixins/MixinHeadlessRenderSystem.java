/*
 * Ostinato: headless does not call SDL_Init, does not record a render thread, and does not unload a
 * library that was never loaded. isOnRenderThread stays true so assertOnRenderThread does not throw
 * on the client thread. Hidden and normal keep the original methods.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.TimeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public class MixinHeadlessRenderSystem {

    // Ostinato: initBackendSystem is SDL_Init plus hints. Headless returns a clock and does not touch SDL.
    @Inject(method = "initBackendSystem", at = @At("HEAD"), cancellable = true)
    private static void ostinato$noSdlInit(CallbackInfoReturnable<TimeSource.NanoTimeSource> ci) {
        if (!HeadlessPolicy.skipSdl()) {
            return;
        }
        ci.setReturnValue(System::nanoTime);
    }

    // Ostinato: the real call records Thread.currentThread as the render thread. Headless skips it.
    @Inject(method = "initRenderThread", at = @At("HEAD"), cancellable = true)
    private static void ostinato$noRenderThread(CallbackInfo ci) {
        if (HeadlessPolicy.skipRenderThread(RunMode.windowCreateMode())) {
            ci.cancel();
        }
    }

    // Ostinato: ticks still call assertOnRenderThread. Headless treats the client thread as allowed.
    @Inject(method = "isOnRenderThread", at = @At("HEAD"), cancellable = true)
    private static void ostinato$onThread(CallbackInfoReturnable<Boolean> ci) {
        if (HeadlessPolicy.skipRenderThread(RunMode.windowCreateMode())) {
            ci.setReturnValue(true);
        }
    }

    // Ostinato: shutdown would unload a backend library that headless never loaded.
    @Inject(method = "unloadTrackedBackendLibrary", at = @At("HEAD"), cancellable = true)
    private static void ostinato$noUnload(CallbackInfo ci) {
        if (HeadlessPolicy.skipSdl()) {
            ci.cancel();
        }
    }
}
