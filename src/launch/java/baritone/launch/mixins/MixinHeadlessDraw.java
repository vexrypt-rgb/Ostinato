/*
 * Ostinato: headless cancels GameRenderer.render, not only renderLevel. The client tick is separate
 * and still runs. Hidden does not take this cancel; it keeps its 1 fps renderLevel reducer.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinHeadlessDraw {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ostinato$render(CallbackInfo ci) {
        // Ostinato: clear, blit, post, and gui live in this method. None of them run.
        if (HeadlessPolicy.skipGpuWorkNow()) {
            ci.cancel();
        }
    }

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), cancellable = true)
    private void ostinato$extract(CallbackInfo ci) {
        // Ostinato: extract looks up atlas sprites for overlays and GUI. Upload was skipped, so this would crash.
        if (HeadlessPolicy.skipGpuWorkNow()) {
            ci.cancel();
        }
    }
}
