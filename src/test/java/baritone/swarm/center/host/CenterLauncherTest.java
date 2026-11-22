package baritone.swarm.center.host;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: {@code #center} starts the standalone window as its own JVM, on the second monitor. */
public class CenterLauncherTest {

    @Test public void launcherCommandLine() {
        List<String> cmd = CenterLauncher.command(Path.of("C:/jdk"), "C:/mods/ostinato.jar", Path.of("C:/swarm/center"), "");
        assertTrue(cmd.get(0).replace('\\', '/').startsWith("C:/jdk/bin/java"));
        assertTrue(cmd.contains("-Dostinato.windowPos=2600,40"));
        assertTrue("small heap, serial GC", cmd.containsAll(CenterLauncher.JVM_FLAGS));
        assertTrue(cmd.contains("baritone.swarm.center.app.CenterApp"));
        assertEquals("--dir", cmd.get(cmd.indexOf("baritone.swarm.center.app.CenterApp") + 1));
    }

    @Test public void theWindowPositionPropertyIsPassedOn() {
        List<String> cmd = CenterLauncher.command(Path.of("C:/jdk"), "x.jar", Path.of("C:/c"), "3000,100");
        assertTrue(cmd.contains("-Dostinato.windowPos=3000,100"));
    }
}
