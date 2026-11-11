/*
 * Ostinato: Blaze3D.getTime is SDL_GetTicksNS. Headless was never SDL_Init'd, so the clock is System.nanoTime.
 * Hidden and normal keep the SDL timer.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.Blaze3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Blaze3D.class)
public class MixinHeadlessClock {

    @Inject(method = "getTime", at = @At("HEAD"), cancellable = true)
    private static void ostinato$time(CallbackInfoReturnable<Double> ci) {
        // Ostinato: seconds, same unit as SDL_GetTicksNS divided by 1e9.
        if (HeadlessPolicy.skipSdl()) {
            ci.setReturnValue(System.nanoTime() / 1_000_000_000.0);
        }
    }
}
