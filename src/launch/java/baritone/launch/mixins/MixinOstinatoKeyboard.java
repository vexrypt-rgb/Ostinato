/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Ostinato: KeyboardHandler.keyPress/charTyped, for typed chat that goes through the real key path. A class mixin, since accessor interfaces can't carry default methods. */
@Mixin(KeyboardHandler.class)
public abstract class MixinOstinatoKeyboard implements IOstinatoInput {
    @Shadow
    private void keyPress(long window, int action, KeyEvent e) {}

    @Shadow
    private void charTyped(long window, CharacterEvent e) {}

    @Override
    public void ostinato$keyPress(long window, int action, KeyEvent e) {
        keyPress(window, action, e);
    }

    @Override
    public void ostinato$charTyped(long window, CharacterEvent e) {
        charTyped(window, e);
    }
}
