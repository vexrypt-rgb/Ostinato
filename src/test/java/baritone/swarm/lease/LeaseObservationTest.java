/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.lease;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.swarm.protocol.CommandProtocol;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c. A lease chosen from a shared observation carries what was observed; the bot checks it in its
 * own world before the command runs. Stale or unknown: the command never runs, is never auto-retried, and the
 * coordinator hears why.
 */
public class LeaseObservationTest {

    private static final String VEIN = "v:o:0,10,0:1,11,1";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LeaseSupport active() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        return s;
    }

    private static List<LeaseMessage> sent(LeaseSupport s, String from, LeaseMessage.Op op) {
        List<LeaseMessage> out = new ArrayList<>();
        for (LeaseSupport.Msg m : s.sent) {
            LeaseMessage lm = m.lease();
            if (lm != null && lm.op == op && from.equals(m.from)) {
                out.add(lm);
            }
        }
        return out;
    }

    @Test
    public void offerExtraCarriesTheObservation() {
        assertEquals("-", LeaseCoordinator.offerExtra(null));
        assertEquals("-", LeaseCoordinator.offerExtra(""));
        assertEquals("ob=iron_ore.4", LeaseCoordinator.offerExtra("iron_ore.4"));
        assertEquals("iron_ore.4", BotLeaseGuard.expectOf("ob=iron_ore.4"));
        assertEquals("iron_ore.4", BotLeaseGuard.expectOf("x=1,ob=iron_ore.4"));
        assertNull(BotLeaseGuard.expectOf("-"));
        assertNull(BotLeaseGuard.expectOf("ob="));
        assertNull(BotLeaseGuard.expectOf(null));
    }

    @Test
    public void observedLeaseOfferSaysWhatWasSeen() throws Exception {
        LeaseSupport s = active();
        s.guard.setObsCheck((res, exp) -> null);
        s.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4");
        s.pump();
        List<LeaseMessage> offers = sent(s, "Alice", LeaseMessage.Op.OFFER);
        assertFalse(offers.isEmpty());
        assertEquals("ob=iron_ore.4", offers.get(0).extra);
    }

    @Test
    public void verifiedObservationRuns() throws Exception {
        LeaseSupport s = active();
        List<String> checked = new ArrayList<>();
        s.guard.setObsCheck((res, exp) -> {
            checked.add(res + " " + exp);
            return null;
        });
        UUID id = LeaseSupport.leaseIdOf(s.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4"));
        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
        assertEquals(1, checked.size());
        assertEquals(VEIN + " iron_ore.4", checked.get(0));
        assertTrue(s.logged("swarm-lease: verified lease=" + id));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        // Ostinato: a lease is checked once; asking again for the same command does not re-check the world.
        assertNull(s.guard.verify(s.bobExec.started.get(0)));
        assertEquals("checked once per lease", 1, checked.size());
    }

    @Test
    public void plainLeaseIsNotChecked() throws Exception {
        LeaseSupport s = active();
        s.guard.setObsCheck((res, exp) -> {
            throw new AssertionError("checked a lease that came from no observation");
        });
        s.coord.lease("Bob", VEIN, "mine iron_ore");
        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
    }

    @Test
    public void staleObservationNeverRunsNorRetriesAndIsReported() throws Exception {
        LeaseSupport s = active();
        List<String> heard = new ArrayList<>();
        s.coord.setObsReportListener((res, reason, bot) -> heard.add(res + " " + reason + " " + bot));
        s.guard.setObsCheck((res, exp) -> BotLeaseGuard.OBS_STALE);
        UUID id = LeaseSupport.leaseIdOf(s.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4"));
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 10, 500L);
        assertEquals("acted on a stale observation", 0, s.bobExec.count("mine"));
        assertTrue(s.logged("(not acting on the shared observation)"));
        assertTrue(s.logged("obs-stale (shared observation not verified locally)"));
        assertEquals(BotLeaseGuard.Phase.DONE, s.guard.phaseOf(id));
        boolean reported = false;
        for (LeaseMessage m : sent(s, "Bob", LeaseMessage.Op.REPORT)) {
            if (id.equals(m.leaseId) && m.extra.contains("reason=obs-stale") && m.extra.contains("obs=stale")) {
                reported = true;
            }
        }
        assertTrue("Bob reports the stale observation", reported);
        assertEquals(1, heard.size());
        assertEquals(VEIN + " obs-stale Bob", heard.get(0));
        assertNull("the lease is released", s.coord.busyReason(VEIN));
    }

    @Test
    public void unknownObservationNeverRuns() throws Exception {
        LeaseSupport s = active();
        s.guard.setObsCheck((res, exp) -> BotLeaseGuard.OBS_UNKNOWN);
        s.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4");
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 10, 500L);
        assertEquals(0, s.bobExec.count("mine"));
        assertTrue(s.logged("obs-unknown (shared observation not verified locally)"));
        assertEquals("rejected once, never retried", 1, s.loggedCount("(shared observation not verified locally)"));
    }

    @Test
    public void noCheckThrowingCheckOrOddAnswerIsUnknown() throws Exception {
        LeaseSupport none = active();
        none.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4");
        none.pump();
        assertEquals(0, none.bobExec.count("mine"));
        assertTrue(none.logged("obs-unknown (shared observation not verified locally)"));

        LeaseSupport thrown = active();
        thrown.guard.setObsCheck((res, exp) -> {
            throw new IllegalStateException("world gone");
        });
        thrown.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4");
        thrown.pump();
        assertEquals(0, thrown.bobExec.count("mine"));
        assertTrue(thrown.logged("obs-unknown (shared observation not verified locally)"));

        LeaseSupport odd = active();
        odd.guard.setObsCheck((res, exp) -> "maybe");
        odd.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4");
        odd.pump();
        assertEquals(0, odd.bobExec.count("mine"));
        assertTrue(odd.logged("obs-unknown (shared observation not verified locally)"));
    }

    /** Deliver only lease messages (offer, accept, grant); protocol commands stay queued, so nothing starts. */
    private static void pumpLeaseOnly(LeaseSupport s) {
        List<LeaseSupport.Msg> batch;
        while (!(batch = s.take(LeaseMessage.TYPE, null)).isEmpty()) {
            for (LeaseSupport.Msg m : batch) {
                s.deliver(m);
            }
        }
    }

    // Ostinato: the mine steers to a leased vein box only while that lease is started, verified and in force.
    @Test
    public void activeVeinOnlyForAStartedVerifiedLease() throws Exception {
        LeaseSupport s = active();
        s.guard.setObsCheck((res, exp) -> null);
        UUID id = LeaseSupport.leaseIdOf(s.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4"));
        pumpLeaseOnly(s);
        assertEquals(0, s.bobExec.count("mine"));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertNull("granted, not started", s.guard.activeVein('o'));

        UUID cmd = null;
        for (LeaseMessage m : sent(s, "Alice", LeaseMessage.Op.OFFER)) {
            if (id.equals(m.leaseId)) {
                cmd = m.commandId;
            }
        }
        assertNotNull(cmd);
        s.guard.started(cmd, "mine");
        assertTrue(s.guard.activeOf(id));
        assertNull("started but the observation never verified", s.guard.activeVein('o'));

        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
        assertEquals(VEIN, String.valueOf(s.guard.activeVein('o')));
        assertNull("other dimension", s.guard.activeVein('n'));

        s.advance(10 * 60_000L); // no tick: the grant runs out, the lease is still marked active
        assertTrue(s.guard.activeOf(id));
        assertNull("past the authorised time", s.guard.activeVein('o'));
    }

    @Test
    public void activeVeinForAPlainVeinLeaseButNotARegionOrAFinishedMine() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", VEIN, "mine iron_ore");
        pumpLeaseOnly(s);
        assertEquals(0, s.bobExec.count("mine"));
        assertNull("granted, not started", s.guard.activeVein('o'));
        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
        assertEquals(VEIN, String.valueOf(s.guard.activeVein('o')));
        s.bobExec.poll = ExecutionState.SUCCEEDED;
        s.tick(); // the protocol polls the executor after the guard ticks: the guard sees the end on the next tick
        s.tick();
        assertNull("the mine ended, the lease is released", s.guard.activeVein('o'));

        LeaseSupport r = active();
        r.coord.lease("Bob", "r:o:0,10,0:1,11,1", "mine iron_ore");
        r.pump();
        assertEquals(1, r.bobExec.count("mine"));
        assertNull("a region is not a vein", r.guard.activeVein('o'));
    }

    @Test
    public void busyReasonCoversOverlappingBoxes() throws Exception {
        LeaseSupport s = active();
        s.guard.setObsCheck((res, exp) -> null);
        assertNull(s.coord.busyReason(VEIN));
        s.coord.lease("Bob", VEIN, "mine iron_ore", "iron_ore.4");
        s.pump();
        assertNotNull(s.coord.busyReason(VEIN));
        assertNotNull("overlapping box", s.coord.busyReason("v:o:1,11,1:2,12,2"));
        assertNull("disjoint box", s.coord.busyReason("v:o:10,10,10:11,11,11"));
    }
}
