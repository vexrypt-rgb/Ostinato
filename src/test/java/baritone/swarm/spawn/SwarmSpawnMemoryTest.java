package baritone.swarm.spawn;

import org.junit.Test;
import static org.junit.Assert.*;

// Ostinato: fake commit readings, never probe Windows in unit tests.
public class SwarmSpawnMemoryTest {
    @Test public void thresholdAndMessage() throws Exception {
        try {
            SwarmSpawnMemory.require(() -> SwarmSpawnMemory.REQUIRED - 1, 1);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("1535 MiB"));
            assertTrue(expected.getMessage().contains("1.5 GB"));
            assertTrue(expected.getMessage().contains("1536 MiB"));
        }
        SwarmSpawnMemory.require(() -> SwarmSpawnMemory.REQUIRED, 1);
        SwarmSpawnMemory.require(() -> SwarmSpawnMemory.REQUIRED + 1, 1);
    }

    @Test public void batchPreflightReservesEachChild() throws Exception {
        try {
            SwarmSpawnMemory.require(() -> SwarmSpawnMemory.REQUIRED, 2);
            fail();
        } catch (IllegalArgumentException expected) {}
        SwarmSpawnMemory.require(() -> SwarmSpawnMemory.REQUIRED * 2, 2);
    }
}
