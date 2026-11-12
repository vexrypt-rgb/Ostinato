package baritone.swarm;

import baritone.api.swarm.ISwarm;
import baritone.swarm.center.UnifiedChat;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Ostinato: the public {@link ISwarm#localAt} hook TenorClef uses for local {@code @} chat. */
public class SwarmApiLocalAtTest {

    @Rule
    public TemporaryFolder dir = new TemporaryFolder();

    @After
    public void reset() {
        UnifiedChat.resetForTests();
    }

    @Test
    public void defaultMethodTellsTheCallerToRunItItself() {
        ISwarm bare = new ISwarm() {
            public boolean isOnline() { return false; }
            public String state() { return "disabled"; }
            public String self() { return null; }
            public java.util.List<baritone.api.swarm.ISwarmGroup> myGroups() { return java.util.List.of(); }
            public baritone.api.swarm.ISwarmGroup group(String id) { return null; }
            public boolean send(String g, String t, String ty, String b, baritone.api.swarm.SwarmPriority p) { return false; }
            public boolean registerHandler(String type, SwarmHandler handler) { return false; }
        };
        AtomicInteger runs = new AtomicInteger();
        assertFalse(bare.localAt("@goto 1 2 3", runs::incrementAndGet));
        assertEquals(0, runs.get());
    }

    @Test
    public void withoutAnInstalledRegistryTheCallerRunsTheBody() {
        AtomicInteger runs = new AtomicInteger();
        assertFalse(new SwarmApi(null).localAt("@goto 1 2 3", runs::incrementAndGet));
        assertEquals(0, runs.get());
    }

    @Test
    public void anInstalledRegistryRunsTheBodyOnce() throws Exception {
        UnifiedChat.install(null, dir.newFolder().toPath());
        AtomicInteger runs = new AtomicInteger();
        assertTrue(new SwarmApi(null).localAt("@goto 1 2 3", runs::incrementAndGet));
        assertEquals(1, runs.get());
    }

    @Test
    public void aBodyExceptionReachesTheCaller() throws Exception {
        UnifiedChat.install(null, dir.newFolder().toPath());
        IllegalStateException boom = new IllegalStateException("boom");
        try {
            new SwarmApi(null).localAt("@goto 1 2 3", () -> { throw boom; });
            fail("expected the body's exception");
        } catch (IllegalStateException thrown) {
            assertSame(boom, thrown);
        }
    }

    @Test
    public void aNullBodyIsNotHandled() throws Exception {
        UnifiedChat.install(null, dir.newFolder().toPath());
        assertFalse(new SwarmApi(null).localAt("@goto 1 2 3", null));
    }
}
