/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoInput;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Ostinato: MouseHandler.onButton and onMove, for menu clicks that move the real cursor. */
@Mixin(MouseHandler.class)
public interface MixinOstinatoMouse extends IOstinatoInput {
    @Invoker("onButton")
    void ostinato$invokeOnButton(long window, MouseButtonInfo b, int action);

    @Invoker("onMove")
    void ostinato$invokeOnMove(long window, double x, double y);

    @Override
    default void ostinato$onButton(long window, MouseButtonInfo b, int action) {
        ostinato$invokeOnButton(window, b, action);
    }

    @Override
    default void ostinato$onMove(long window, double x, double y) {
        ostinato$invokeOnMove(window, x, y);
    }
}
