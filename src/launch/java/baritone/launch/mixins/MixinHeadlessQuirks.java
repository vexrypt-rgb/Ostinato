/*
 * Ostinato: the quit shortcut reads SDL modifier state before any key. Headless reports it up.
 * Hidden and normal still read the keyboard.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import net.minecraft.client.input.InputQuirks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputQuirks.class)
public class MixinHeadlessQuirks {

    @Inject(method = "isQuitShortcutDown", at = @At("HEAD"), cancellable = true)
    private static void ostinato$quit(CallbackInfoReturnable<Boolean> ci) {
        // Ostinato: SDL_GetModState is the first instruction. Do not call it.
        if (HeadlessPolicy.skipSdl()) {
            ci.setReturnValue(false);
        }
    }
}
