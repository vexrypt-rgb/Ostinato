/*
 * Ostinato: headless does not build glyph providers or upload font textures.
 * The reload future completes so the resource reload does not wait on it.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(FontManager.class)
public class MixinHeadlessFont {

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void ostinato$reload(
            PreparableReloadListener.SharedState state,
            Executor prepare,
            PreparableReloadListener.PreparationBarrier barrier,
            Executor apply,
            CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        if (!HeadlessPolicy.skipGpuWorkNow()) {
            return;
        }
        // Ostinato: join the barrier so the pack reload is not stuck waiting, and do not build glyph textures.
        ci.setReturnValue(barrier.wait(null));
    }
}
