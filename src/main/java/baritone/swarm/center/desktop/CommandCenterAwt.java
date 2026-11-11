package baritone.swarm.center.desktop;

import baritone.api.run.RunMode;

/**
 * Ostinato: Minecraft 26.3 {@code Main} class init forces {@code java.awt.headless=true}
 * because the game uses SDL, not AWT. The command-center Swing window needs AWT on
 * visible clients. Deep headless keeps the vanilla property.
 */
public final class CommandCenterAwt {

    private CommandCenterAwt() {}

    /** Call from {@code Main} class init, after vanilla sets the property, before AWT starts. */
    public static void allowWindow() {
        if (RunMode.windowCreateMode() == RunMode.Value.HEADLESS) {
            return;
        }
        System.setProperty("java.awt.headless", "false");
    }
}
