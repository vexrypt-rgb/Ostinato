/*
 * Ostinato: skip animation-frame uploads while hidden. TextureAtlas.upload and uploadInitialContents still run,
 * so the render thread keeps a base atlas to sample.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class MixinHiddenAnimation {

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true)
    private void ostinato$skipAnim(CallbackInfo ci) {
        // Ostinato: cycleAnimationFrames is what calls the private uploadAnimationFrames.
        RunMode.Value mode = RunMode.current();
        // Ostinato: hidden skips animation uploads. Headless does too. TextureAtlas.upload is not cancelled.
        if (HiddenRenderPolicy.skipTextureUpload(mode, RenderSwap.isPresenting())
                || HeadlessPolicy.skipTextureAnimation(mode)) {
            ci.cancel();
        }
    }
}
