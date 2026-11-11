/*
 * Ostinato: key and mouse-grab queries go to SDL. Headless reports keys up and does not grab.
 * Baritone writes player input itself. Hidden and normal still read the keyboard.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
public class MixinHeadlessInput {

    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void ostinato$key(int key, CallbackInfoReturnable<Boolean> ci) {
        if (HeadlessPolicy.skipSdl()) {
            ci.setReturnValue(false);
        }
    }

    @Inject(method = {"grabMouse", "releaseMouse"}, at = @At("HEAD"), cancellable = true)
    private static void ostinato$grab(Window window, double x, double y, CallbackInfo ci) {
        // Ostinato: these call SDL_SetWindowRelativeMouseMode. The OS cursor stays where it is.
        if (HeadlessPolicy.skipSdl()) {
            ci.cancel();
        }
    }
}
