package baritone.swarm.spawn;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

// Ostinato: verify shutdown diagnostics without starting a watcher or exiting the JVM.
public class SwarmSpawnParentWatcherTest {
    @Test
    public void announcesParentPidAndFlushes() {
        boolean[] flushed = {false};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                flushed[0] = true;
                super.flush();
            }
        };
        PrintStream out = new PrintStream(bytes, false, StandardCharsets.UTF_8);
        SwarmSpawnParentWatcher.announceParentExit(12345L, out);
        String message = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("12345"));
        assertTrue(message.contains("exiting"));
        assertTrue(flushed[0]);
    }
}
