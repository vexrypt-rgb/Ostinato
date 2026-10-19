/*
 * Ostinato: new file (auto-spectate on 1.21.4). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.KeyboardHandler;

/** Ostinato: KeyboardHandler.keyPress/charTyped, for typed chat that goes through the real key path. */
@Mixin(KeyboardHandler.class)
public abstract class MixinOstinatoKeyboard implements IOstinatoInput {
    @Shadow
    public void keyPress(long window, int key, int scancode, int action, int mods) {}

    @Shadow
    private void charTyped(long window, int codepoint, int mods) {}

    @Override
    public void ostinato$keyPress(long window, int key, int scancode, int action, int mods) {
        keyPress(window, key, scancode, action, mods);
    }

    @Override
    public void ostinato$charTyped(long window, int codepoint, int mods) {
        charTyped(window, codepoint, mods);
    }
}
