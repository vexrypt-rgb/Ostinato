package baritone.swarm.lease;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: Phase 3 read API. The coordinator's and the bot guard's feed records say what their {@code describe()}
 * lines say, field for field, and reading them changes nothing.
 */
public class FeedLeasesTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void coordinatorLeasesMatchDescribe() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        String r = s.coord.lease("Bob", "r:o:0,60,0:15,70,15", "mine iron_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        List<String> before = s.coord.describe();
        List<CoordinatorFeed.Lease> feed = s.coord.feedLeases();
        assertEquals(before, s.coord.describe());
        assertEquals(1, feed.size());
        CoordinatorFeed.Lease l = feed.get(0);
        assertEquals(id.toString(), l.id());
        assertEquals("region", l.type());
        assertEquals('o', l.dim());
        assertArrayEquals(new int[]{0, 60, 0, 15, 70, 15}, l.box());
        assertEquals("Bob", l.bot());
        assertEquals("granted", l.state());
        assertEquals("mine iron_ore", l.command());
        String line = before.stream().filter(x -> x.contains(id.toString())).findFirst().orElseThrow();
        assertTrue(line, line.contains("gen=" + l.gen()) && line.contains("state=" + l.state()) && line.contains("bot=" + l.bot()));
        assertEquals("active", s.coord.feedMode());
    }

    // Ostinato: live check p3w run 7 (M2). A released lease left the feed the moment it ended, so the war-room map
    // could never draw it faded with its end reason; an expired one already stayed for a while.
    @Test public void aReleasedLeaseStaysInTheFeedForAMinute() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        String r = s.coord.lease("Bob", "r:o:0,60,0:15,70,15", "mine iron_ore");
        s.pump();
        String id = LeaseSupport.leaseIdOf(r).toString();
        assertEquals(1, s.bobExec.count("mine"));
        s.bobExec.poll = baritone.api.swarm.protocol.ExecutionState.SUCCEEDED;
        for (int i = 0; i < 4; i++) {
            s.tick();
            s.pump();
        }
        List<CoordinatorFeed.Lease> ended = s.coord.feedLeases();
        assertEquals(ended.toString(), 1, ended.size());
        assertEquals(id, ended.get(0).id());
        assertEquals("released", ended.get(0).state());
        assertTrue(!ended.get(0).live());
        s.advance(61_000L);
        assertTrue(s.coord.feedLeases().stream().noneMatch(l -> l.id().equals(id)));
    }

    @Test public void anUntypedKeyHasNoBox() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        CoordinatorFeed.Lease l = s.coord.feedLeases().get(0);
        assertEquals("key", l.type());
        assertNull(l.box());
    }

    @Test public void guardLeasesMatchDescribe() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        List<BotFeed.HeldLease> held = s.guard.feedLeases();
        assertEquals(1, held.size());
        BotFeed.HeldLease h = held.get(0);
        assertEquals(LeaseSupport.leaseIdOf(r).toString(), h.id());
        assertEquals("valid", h.phase());
        assertTrue(h.leftMs() > 0);
        String line = s.guard.describe().get(0);
        assertTrue(line, line.contains("lease=" + h.id()) && line.contains("phase=valid") && line.contains("gen=" + h.gen()));
    }
}
