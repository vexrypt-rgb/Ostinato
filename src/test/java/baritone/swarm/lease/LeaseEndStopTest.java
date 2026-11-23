package baritone.swarm.lease;

import baritone.api.swarm.protocol.ExecutionState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: slice 3 S5b fix (p2c2 rerun 2). Any lease end - refused at entry, revoked, expired, released - stops the
 * leased process and cancels the path segment it was executing before the bot's next action, and until the bot is idle
 * the fence denies breaks and places inside that lease's box (unless a current lease covers the spot). Live, the
 * refusal ended the lease but the segment already executing broke two blocks inside the region about 2 s later.
 */
public class LeaseEndStopTest {

    private static final String REGION = "r:o:0,10,0:9,12,9";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The bot as the stop sees it: a path segment executing, which breaks one planned block per tick. */
    static final class FakeBot implements LeaseEndStop.Bot {
        boolean executing = true, safe = true, other;
        int stops, cancels;
        final List<String> calls = new ArrayList<>();

        @Override
        public void stopLeasedProcesses() {
            stops++;
            calls.add("stop");
        }

        @Override
        public boolean otherProcessInControl() {
            return other;
        }

        @Override
        public boolean cancelSegmentIfSafe() {
            if (!safe) {
                return false;
            }
            executing = false;
            cancels++;
            calls.add("cancel");
            return true;
        }

        @Override
        public boolean idle() {
            return !executing;
        }
    }

    private LeaseSupport started() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        s.coord.lease("Bob", REGION, "tunnel 2 1 8");
        s.pump();
        assertEquals(1, s.bobExec.count("tunnel"));
        return s;
    }

    /** The executing segment tries its next break: true if it went through. */
    private static boolean nextBreak(LeaseEndStop stop, FakeBot bot, int x, int y, int z, List<String> calls) {
        if (!bot.executing) {
            return false; // the segment is gone: nothing to break
        }
        boolean denied = stop.denied(LeaseFence.Kind.BREAK, 'o', x, y, z);
        calls.add((denied ? "denied " : "broke ") + x + "," + y + "," + z);
        return !denied;
    }

    @Test
    public void theSegmentAlreadyExecutingWhenTheLeaseIsRefusedAtEntryBreaksNothingMore() throws Exception {
        LeaseSupport s = started();
        FakeBot bot = new FakeBot();
        LeaseEndStop stop = new LeaseEndStop(s.guard, bot, s.log::add);
        // the segment's first break inside the region: the entry check refuses it (not loaded) and ends the lease
        assertNotNull(s.guard.regionEntry(LeaseFence.Kind.BREAK, 'o', 0, 10, 5, k -> false));
        // the same segment goes on to its next planned breaks, as it did live (300,150 then 301,150 then the floor)
        assertFalse("the next break inside the box", nextBreak(stop, bot, 1, 10, 5, bot.calls));
        assertFalse(nextBreak(stop, bot, 1, 9, 5, bot.calls));
        assertEquals("stopped and the segment cancelled before its next break, which is denied; nothing after",
                List.of("stop", "cancel", "denied 1,10,5"), bot.calls);
        assertTrue(s.logged("in-flight path cancelled"));
    }

    @Test
    public void aRevokedLeaseStopsTheSegmentAndFencesItsBox() throws Exception {
        LeaseSupport s = started();
        FakeBot bot = new FakeBot();
        LeaseEndStop stop = new LeaseEndStop(s.guard, bot, s.log::add);
        s.coord.revoke(REGION);
        s.pump();
        assertTrue(s.guard.endCancelPending() || bot.cancels > 0);
        assertTrue("inside the ended box", s.guard.insideEndedBox('o', 5, 10, 5));
        assertFalse("outside it", s.guard.insideEndedBox('o', 50, 10, 50));
        bot.safe = false; // mid-jump: the cancel has to wait, the fence holds meanwhile
        assertTrue(stop.denied(LeaseFence.Kind.PLACE, 'o', 5, 11, 5));
        assertTrue(s.guard.endCancelPending());
        bot.safe = true;
        stop.tick();
        assertFalse(s.guard.endCancelPending());
        assertEquals(1, bot.cancels);
    }

    @Test
    public void anExpiredLeaseStopsTheSegment() throws Exception {
        LeaseSupport s = started();
        FakeBot bot = new FakeBot();
        LeaseEndStop stop = new LeaseEndStop(s.guard, bot, s.log::add);
        s.advance(10 * 60_000L);
        s.guard.tick();
        assertTrue(s.guard.endCancelPending());
        assertTrue(stop.denied(LeaseFence.Kind.BREAK, 'o', 5, 10, 5));
        assertEquals(1, bot.cancels);
    }

    @Test
    public void aReleasedLeaseStopsTheSegmentToo() throws Exception {
        LeaseSupport s = started();
        FakeBot bot = new FakeBot();
        LeaseEndStop stop = new LeaseEndStop(s.guard, bot, s.log::add);
        s.bobExec.poll = ExecutionState.SUCCEEDED; // the tunnel ended itself (it records an outcome)
        s.tick(); // the protocol polls the executor after the guard ticks: the guard sees the end on the next tick
        s.tick();
        assertTrue(s.logged("swarm-lease: released lease="));
        assertTrue(s.guard.endCancelPending());
        stop.tick();
        assertEquals(1, bot.cancels);
    }

    @Test
    public void onceIdleTheBoxIsNoLongerFenced() throws Exception {
        LeaseSupport s = started();
        FakeBot bot = new FakeBot();
        LeaseEndStop stop = new LeaseEndStop(s.guard, bot, s.log::add);
        s.coord.revoke(REGION);
        s.pump();
        stop.tick(); // cancels; the bot is idle
        stop.tick();
        assertFalse(s.guard.insideEndedBox('o', 5, 10, 5));
        assertFalse(stop.denied(LeaseFence.Kind.BREAK, 'o', 5, 10, 5));
    }

    @Test
    public void aNewLeaseOnTheBoxLiftsTheBackstop() throws Exception {
        LeaseSupport s = started();
        s.coord.revoke(REGION);
        s.pump();
        assertTrue(s.guard.insideEndedBox('o', 5, 10, 5));
        for (int i = 0; i < 3; i++) {
            s.tick(); // the revoked command's stop is confirmed
        }
        s.coord.lease("Bob", REGION, "tunnel 2 1 8"); // generation 2
        for (int i = 0; i < 3; i++) {
            s.tick();
        }
        assertEquals("the new leased command started", 2, s.bobExec.count("tunnel"));
        assertFalse("covered by a current lease", s.guard.insideEndedBox('o', 5, 10, 5));
    }

    @Test
    public void anotherProcessInControlKeepsItsPath() throws Exception {
        LeaseSupport s = started();
        FakeBot bot = new FakeBot();
        bot.other = true; // e.g. survival took over: its path is not the leased one
        LeaseEndStop stop = new LeaseEndStop(s.guard, bot, s.log::add);
        s.coord.revoke(REGION);
        s.pump();
        stop.tick();
        assertEquals(1, bot.stops);
        assertEquals("no segment cancel", 0, bot.cancels);
        assertFalse(s.guard.endCancelPending());
        assertTrue("the ended box is still fenced", s.guard.insideEndedBox('o', 5, 10, 5));
    }

    @Test
    public void containerActionsAreNotDeniedByTheBackstop() throws Exception {
        LeaseSupport s = started();
        FakeBot bot = new FakeBot();
        LeaseEndStop stop = new LeaseEndStop(s.guard, bot, s.log::add);
        s.coord.revoke(REGION);
        s.pump();
        assertFalse(stop.denied(LeaseFence.Kind.CONTAINER, 'o', 5, 10, 5));
    }
}
