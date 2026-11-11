/*
 * Ostinato: the game loop polls SDL every frame. Headless drops those calls. The constructor of
 * this class does not touch SDL. Hidden and normal still poll.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.platform.SDLEventHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SDLEventHandler.class)
public class MixinHeadlessEvents {

    @Inject(method = {"pollEvents", "pumpEvents", "flushInputEvents"}, at = @At("HEAD"), cancellable = true)
    private void ostinato$noPoll(CallbackInfo ci) {
        // Ostinato: flushInputEvents runs from the Minecraft constructor, before the first tick.
        if (HeadlessPolicy.skipSdl()) {
            ci.cancel();
        }
    }
}
