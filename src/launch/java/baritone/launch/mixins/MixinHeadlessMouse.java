/*
 * Ostinato: resyncMousePosition reads the OS cursor and the window position. Headless skips it.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MixinHeadlessMouse {

    @Inject(method = "resyncMousePosition", at = @At("HEAD"), cancellable = true)
    private void ostinato$resync(CallbackInfo ci) {
        if (HeadlessPolicy.skipSdl()) {
            ci.cancel();
        }
    }
}
