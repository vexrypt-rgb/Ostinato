package baritone.launch.mixins;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ostinato test-harness only, inert unless -Dostinato.windowPos or -Dostinato.noMouseGrab=true is set: grabbing or
 * releasing the mouse only flips the game's own flag and never touches the OS cursor mode or position, so a
 * background test client cannot teleport the user's real cursor. Bot rotations don't use the OS mouse.
 */
@Mixin(MouseHandler.class)
public abstract class MixinNoMouseGrab {

    @Shadow private boolean mouseGrabbed;

    private static boolean ostinato$off() {
        return System.getProperty("ostinato.windowPos") != null || Boolean.getBoolean("ostinato.noMouseGrab");
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
