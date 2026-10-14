package baritone.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

/** Modifier-key polling (the static Screen helpers were removed in 1.21.11). */
final class Keys {
    private Keys() {}

    private static boolean down(int a, int b) {
        return InputConstants.isKeyDown(a) || InputConstants.isKeyDown(b);
    }

    static boolean shift() { return down(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT); }

    static boolean ctrl() { return down(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL); }
}
