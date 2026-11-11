package baritone.launch.mixins;

import baritone.api.hidden.HiddenWindowPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ostinato: grabbing or releasing the mouse only flips the game's own flag and never touches the OS cursor, when
 * hidden mode is on, a render-swap is presenting, {@code -Dostinato.windowPos} is set, or
 * {@code -Dostinato.noMouseGrab=true}. A normal client with none of those leaves the OS cursor alone as before.
 * Bot rotations don't use the OS mouse.
 */
@Mixin(MouseHandler.class)
public abstract class MixinNoMouseGrab {

    @Shadow private boolean mouseGrabbed;

    private static boolean ostinato$off() {
        // Ostinato: hidden cannot teleport the user's cursor. Normal without the old flags is unchanged.
        return HiddenWindowPolicy.suppressMouseGrab(
                RunMode.current(),
                RenderSwap.isPresenting(),
                System.getProperty("ostinato.windowPos") != null,
                Boolean.getBoolean("ostinato.noMouseGrab"));
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void ostinato$grab(CallbackInfo ci) {
        if (!ostinato$off()) return;
        mouseGrabbed = true;
        ci.cancel();
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
    private void ostinato$release(CallbackInfo ci) {
        if (!ostinato$off()) return;
        mouseGrabbed = false;
        ci.cancel();
    }
}
