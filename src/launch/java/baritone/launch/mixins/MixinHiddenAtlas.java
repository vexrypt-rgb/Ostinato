/*
 * Ostinato: while hidden, stitch atlases at mip 0. Hidden still uploads the base atlas. Headless forces mip 0
 * here and cancels TextureAtlas.upload in MixinHeadlessAtlas, so nothing is sent to a device.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.renderer.texture.SpriteLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SpriteLoader.class)
public class MixinHiddenAtlas {

    @ModifyVariable(method = "loadAndStitch", at = @At("HEAD"), argsOnly = true)
    private int ostinato$mip(int mip) {
        // Ostinato: loadAndStitch has one int, the mip level. Hidden or headless forces 0. Headless then skips upload.
        RunMode.Value mode = RunMode.current();
        int hidden = HiddenRenderPolicy.atlasMipLevel(mode, RenderSwap.isPresenting(), mip);
        return HeadlessPolicy.atlasMipLevel(mode, hidden);
    }
}
