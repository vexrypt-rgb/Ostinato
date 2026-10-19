/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Ostinato: KeyboardHandler.keyPress, for typed chat that goes through the real key path. */
@Mixin(KeyboardHandler.class)
public interface MixinOstinatoKeyboard extends IOstinatoInput {
    @Invoker("keyPress")
    void ostinato$invokeKeyPress(long window, int action, KeyEvent e);

    @Invoker("charTyped")
    void ostinato$invokeCharTyped(long window, net.minecraft.client.input.CharacterEvent e);

    @Override
    default void ostinato$charTyped(long window, net.minecraft.client.input.CharacterEvent e) {
        ostinato$invokeCharTyped(window, e);
    }

    @Override
    default void ostinato$keyPress(long window, int action, KeyEvent e) {
        ostinato$invokeKeyPress(window, action, e);
    }
}
