/*
 * Ostinato: new file (auto-spectate on 1.21.11). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoInput;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Ostinato: MouseHandler.onButton/onMove, for menu clicks that go through the real mouse path. */
@Mixin(MouseHandler.class)
public abstract class MixinOstinatoMouse implements IOstinatoInput {
    @Shadow
    private void onButton(long window, MouseButtonInfo b, int action) {}

    @Shadow
    private void onMove(long window, double x, double y) {}

    @Override
    public void ostinato$onButton(long window, MouseButtonInfo b, int action) {
        onButton(window, b, action);
    }

    @Override
    public void ostinato$onMove(long window, double x, double y) {
        onMove(window, x, y);
    }
}
