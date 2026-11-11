package baritone.swarm.center.desktop;

import baritone.api.run.RunMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ostinato: AWT is restored for the command-center window except in deep headless. */
public class CommandCenterAwtTest {

    @Test
    public void headlessKeepsTheVanillaProperty() {
        runWith("headless", "true", "true");
    }

    @Test
    public void normalClearsTheVanillaHeadlessFlag() {
        runWith("normal", "true", "false");
    }

    @Test
    public void hiddenClearsTheVanillaHeadlessFlag() {
        runWith("hidden", "true", "false");
    }

    private static void runWith(String mode, String start, String expect) {
        String previousMode = System.getProperty(RunMode.PROPERTY);
        String previousHeadless = System.getProperty("java.awt.headless");
        try {
            System.setProperty(RunMode.PROPERTY, mode);
            System.setProperty("java.awt.headless", start);
            CommandCenterAwt.allowWindow();
            assertEquals(expect, System.getProperty("java.awt.headless"));
        } finally {
            restore(RunMode.PROPERTY, previousMode);
            restore("java.awt.headless", previousHeadless);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
