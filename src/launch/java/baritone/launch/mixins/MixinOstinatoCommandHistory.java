/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoScreens;
import net.minecraft.client.CommandHistory;
import net.minecraft.util.ArrayListDeque;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

/** Ostinato: keeps /register and /login out of command_history.txt. */
@Mixin(CommandHistory.class)
public interface MixinOstinatoCommandHistory extends IOstinatoScreens {
    @Accessor("lastCommands")
    ArrayListDeque<String> ostinato$lastCommands();

    @Invoker("save")
    void ostinato$save();

    @Override
    default void ostinato$scrub(Predicate<String> drop) {
        if (ostinato$lastCommands().removeIf(drop)) ostinato$save();
    }
}
