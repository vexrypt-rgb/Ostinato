/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Ostinato: where a menu's slots are drawn. */
@Mixin(AbstractContainerScreen.class)
public interface MixinOstinatoContainerScreen extends IOstinatoScreens {
    @Accessor("leftPos")
    int ostinato$left();

    @Accessor("topPos")
    int ostinato$top();

    @Override
    default int[] ostinato$pos() {
        return new int[]{ostinato$left(), ostinato$top()};
    }
}
