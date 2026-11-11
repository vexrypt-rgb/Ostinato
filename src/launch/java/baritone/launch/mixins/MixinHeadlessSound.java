/*
 * Ostinato: while headless, do not open the OpenAL playback device.
 * SoundEngine.reload is what calls Library.init. play() returns not-started while the engine was never loaded.
 * The SoundEngine object still exists, and tick() still runs. Normal and hidden do not take this path.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.run.RunMode;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public class MixinHeadlessSound {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipReload(CallbackInfo ci) {
        // Ostinato: skip Library.init. Volume 0 is a separate mixin. The engine object is not destroyed here.
        if (HeadlessPolicy.skipSoundDeviceOpen(RunMode.current())) {
            ci.cancel();
        }
    }
}
