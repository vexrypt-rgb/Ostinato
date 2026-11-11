/*
 * Ostinato: headless does not upload the stitched atlas. The stitch mip is already forced to 0
 * by the hidden atlas mixin. Hidden still uploads the base atlas.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class MixinHeadlessAtlas {

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void ostinato$upload(CallbackInfo ci) {
        // Ostinato: uploadInitialContents and the GPU write live under this method.
        if (HeadlessPolicy.skipGpuWorkNow()) {
            ci.cancel();
        }
    }
}
