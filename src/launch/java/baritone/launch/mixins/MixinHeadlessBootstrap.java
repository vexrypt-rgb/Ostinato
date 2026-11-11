/*
 * Ostinato: headless does not load the SDL native. The GL backend is skipped separately, in Minecraft's constructor.
 * Hidden and normal still load both.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeLibrariesBootstrap.class)
public class MixinHeadlessBootstrap {

    @Inject(method = "loadSdl", at = @At("HEAD"), cancellable = true)
    private static void ostinato$sdl(CallbackInfo ci) {
        // Ostinato: SDL.getLibrary() is the body of this method. Headless does not call it.
        if (HeadlessPolicy.skipSdl()) {
            ci.cancel();
        }
    }
}
