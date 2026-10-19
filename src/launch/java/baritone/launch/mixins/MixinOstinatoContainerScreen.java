/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Ostinato: menu origin, to turn a slot into a screen point for humanized clicks. */
@Mixin(AbstractContainerScreen.class)
public abstract class MixinOstinatoContainerScreen implements IOstinatoScreens {
    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Override
    public int[] ostinato$pos() {
        return new int[]{leftPos, topPos};
    }
}
