/*
 * Ostinato: text input starts an SDL IME session on the window. Headless does not. Chat packets
 * are not sent through this class.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.platform.TextInputManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextInputManager.class)
public class MixinHeadlessText {

    @Inject(method = {"setTextInputArea", "startTextInput", "stopTextInput", "applyTextInputArea"}, at = @At("HEAD"), cancellable = true)
    private void ostinato$ime(CallbackInfo ci) {
        if (HeadlessPolicy.skipSdl()) {
            ci.cancel();
        }
    }
}
