/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoScreens;
import net.minecraft.client.CommandHistory;
import net.minecraft.util.ArrayListDeque;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Predicate;

/** Ostinato: drop /register and /login from the saved command history. */
@Mixin(CommandHistory.class)
public abstract class MixinOstinatoCommandHistory implements IOstinatoScreens {
    @Shadow
    @Final
    private ArrayListDeque<String> lastCommands;

    @Shadow
    private void save() {}

    @Override
    public void ostinato$scrub(Predicate<String> drop) {
        if (lastCommands.removeIf(drop)) save();
    }
}
