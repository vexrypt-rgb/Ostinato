package baritone.swarm.center.app;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the design doc's focus rules and the window's independence, checked on the sources. The window never
 * raises or focuses itself, never opens a dialog, beeps or flashes the taskbar; and the window, feed and channel
 * packages never import Minecraft or Baritone core (the center-app build would not compile them, but this names the
 * file).
 */
public class SourceLintTest {

    static final String[] FORBIDDEN = {".toFront(", "requestFocus(", "requestFocusInWindow(", "setAlwaysOnTop(",
            "Toolkit.getDefaultToolkit().beep", ".beep()", "JOptionPane", "JDialog", "requestUserAttention",
            "Taskbar", "SystemTray", "AudioSystem", "Clip "};

    static List<Path> sources(String pkg) throws IOException {
        Path root = Path.of(System.getProperty("ostinato.src", "../src")).resolve("main/java/" + pkg);
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test public void theWindowNeverTakesFocusOrMakesNoise() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : sources("baritone/swarm/center/app")) {
            String text = Files.readString(p);
            for (String f : FORBIDDEN) {
                if (text.contains(f)) {
                    bad.add(p.getFileName() + ": " + f);
                }
            }
        }
        assertTrue(bad.toString(), bad.isEmpty());
    }

    @Test public void theWindowFeedAndChannelImportNoMinecraft() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String pkg : new String[]{"baritone/swarm/center/app", "baritone/swarm/center/feed",
                "baritone/swarm/center/channel"}) {
            for (Path p : sources(pkg)) {
                for (String line : Files.readAllLines(p)) {
                    if (line.startsWith("import net.minecraft") || line.startsWith("import baritone.Baritone")
                            || line.startsWith("import baritone.behavior") || line.startsWith("import com.google")) {
                        bad.add(p.getFileName() + ": " + line);
                    }
                }
            }
        }
        assertTrue(bad.toString(), bad.isEmpty());
    }
}
