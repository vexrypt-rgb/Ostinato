package baritone.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Modifier-key polling (the static Screen helpers were removed in 1.21.11). */
final class Keys {
    private Keys() {}

    private static boolean down(int a, int b) {
        var h = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(h, a) || InputConstants.isKeyDown(h, b);
    }

    static boolean shift() { return down(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT); }

    static boolean ctrl() { return down(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL); }
}
