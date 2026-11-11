package baritone.swarm.spawn;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

// Ostinato: pure grammar tests, no Minecraft or processes.
public class SwarmSpawnArgsTest {
    @Test public void optionalCountAndServer() {
        assertEquals(List.of("Bot"), SwarmSpawnArgs.parse(List.of("Bot")).names());
        assertNull(SwarmSpawnArgs.parse(List.of("Bot")).server());
        assertEquals(List.of("Bot1", "Bot2"), SwarmSpawnArgs.parse(List.of("Bot", "2")).names());
        assertEquals("localhost:25566", SwarmSpawnArgs.parse(List.of("Bot", "2", "localhost:25566")).server());
        assertEquals("example.org", SwarmSpawnArgs.parse(List.of("Bot", "example.org")).server());
    }

    @Test public void rejectsInvalidGrammar() {
        for (List<String> args : List.of(List.<String>of(), List.of("bad-name"), List.of("abcdefghijklmnopq"),
                List.of("Bot", "0"), List.of("Bot", "-1"), List.of("Bot", "17"),
                List.of("Bot", "99999999999999999"), List.of("Bot", "server", "extra"))) {
            try {
                SwarmSpawnArgs.parse(args);
                fail("Accepted " + args);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test public void expandedNamesRemainUniqueAndValid() {
        List<String> names = SwarmSpawnArgs.parse(List.of("abcdefghijklmnop", "16")).names();
        assertEquals(16, new HashSet<>(names).size());
        for (String name : names) assertEquals(name, SwarmSpawnArgs.name(name));
        assertEquals("abcdefghijklmno1", names.get(0));
        assertEquals("abcdefghijklmn16", names.get(15));
    }

    @Test public void accountIsNotAServerAndOneAccountCannotCoverACount() {
        SwarmSpawnArgs chosen = SwarmSpawnArgs.parse(List.of("Bot", "account:Steve"));
        assertNull(chosen.server());
        assertEquals("Steve", chosen.account());
        assertEquals("example.org", SwarmSpawnArgs.parse(List.of("Bot", "example.org", "account:Steve")).server());
        assertNull(SwarmSpawnArgs.parse(List.of("Bot", "example.org")).account());
        try {
            SwarmSpawnArgs.parse(List.of("Bot", "2", "account:Steve"));
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("One account"));
        }
    }

    @Test public void despawnNameOrAll() {
        assertEquals("Bot", SwarmSpawnArgs.despawn(List.of("Bot")));
        assertEquals("all", SwarmSpawnArgs.despawn(List.of("ALL")));
        for (List<String> args : List.of(List.<String>of(), List.of("bad-name"), List.of("Bot", "other"))) {
            try { SwarmSpawnArgs.despawn(args); fail(); } catch (IllegalArgumentException expected) {}
        }
    }
}
