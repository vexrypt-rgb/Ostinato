/*
 * Ostinato: the clipboard is an SDL call. Headless reads empty and drops writes.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.platform.ClipboardManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClipboardManager.class)
public class MixinHeadlessClipboard {

    @Inject(method = "getClipboard", at = @At("HEAD"), cancellable = true)
    private void ostinato$get(CallbackInfoReturnable<String> ci) {
        if (HeadlessPolicy.skipSdl()) {
            ci.setReturnValue("");
        }
    }

    @Inject(method = "setClipboard", at = @At("HEAD"), cancellable = true)
    private void ostinato$set(String text, CallbackInfo ci) {
        if (HeadlessPolicy.skipSdl()) {
            ci.cancel();
        }
    }
}
