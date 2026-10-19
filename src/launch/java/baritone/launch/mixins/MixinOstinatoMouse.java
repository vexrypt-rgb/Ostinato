/*
 * Ostinato: new file (auto-spectate on 1.21.4). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.utils.accessor.IOstinatoInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.MouseHandler;

/** Ostinato: MouseHandler.onPress/onMove, for menu clicks that go through the real mouse path. */
@Mixin(MouseHandler.class)
public abstract class MixinOstinatoMouse implements IOstinatoInput {
    @Shadow
    private void onPress(long window, int button, int action, int mods) {}

    @Shadow
    private void onMove(long window, double x, double y) {}

    @Override
    public void ostinato$onButton(long window, int button, int action, int mods) {
        onPress(window, button, action, mods);
    }

    @Override
    public void ostinato$onMove(long window, double x, double y) {
        onMove(window, x, y);
    }
}
