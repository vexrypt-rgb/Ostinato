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

import baritone.swarm.protocol.CommandProtocol;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Ostinato: Phase 2a bot-side guard. */
public class BotLeaseGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LeaseSupport active() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        return s;
    }

    @Test
    public void commandBeforeGrantIsHeldThenStarts() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        UUID id = LeaseSupport.leaseIdOf(r);
        // Deliver the OFFER only; the bot sends REQ.
        List<LeaseSupport.Msg> offers = s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER);
        for (LeaseSupport.Msg m : offers) {
            s.deliver(m);
        }
        List<LeaseSupport.Msg> reqs = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REQ);
        for (LeaseSupport.Msg m : reqs) {
            s.deliver(m);
        }
        // Coordinator granted and sent SWC. Deliver the SWC before the GRANT.
        List<LeaseSupport.Msg> grants = s.take(LeaseMessage.TYPE, LeaseMessage.Op.GRANT);
        assertFalse(grants.isEmpty());
        s.pump();
        assertEquals(0, s.bobExec.count("mine"));
        for (LeaseSupport.Msg m : grants) {
            s.deliver(m);
        }
        s.tick();
        assertEquals(1, s.bobExec.count("mine"));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
    }

    @Test
    public void commandWithoutGrantRejectedNolease() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.dropTo.add("Bob");
        s.pump(); // offer lost
        s.dropTo.clear();
        s.aliceP.submit("Bob", "mine diamond_ore");
        s.pump();
        assertEquals(0, s.bobExec.count("mine"));
        s.run(CommandProtocol.HOLD_MS + 500L, 250L);
        assertEquals(0, s.bobExec.count("mine"));
        assertTrue(s.acks.toString(), s.ackContains("nolease"));
    }

    @Test
    public void unleasedCommandsBypass() throws Exception {
        LeaseSupport s = active();
        assertFalse(s.guard.needsLease("goto", null));
        assertFalse(s.guard.needsLease("bastion", null));
        assertTrue(s.guard.needsLease("mine", null));
        assertTrue(s.guard.needsLease("tunnel", null));
    }

    @Test
    public void expiredLeaseFencesAndStops() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        assertFalse(s.guard.invalidActive());
        s.dropTo.add("Alice"); // renewals unanswered
        s.dropTo.add("Bob");
        s.run(31_000L, 1_000L);
        assertNotEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertTrue(s.logged("lease-expired"));
        assertFalse(s.stopCalls.isEmpty());
        assertTrue(s.logged("stopped lease="));
    }

    @Test
    public void invalidActiveWhileStopUnconfirmed() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.stopOk = false;
        s.dropTo.add("Alice");
        s.dropTo.add("Bob");
        s.run(31_000L, 1_000L);
        assertTrue(s.guard.invalidActive());
    }

    @Test
    public void renewFailureLogsOnceAndBacksOff() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.dropTo.add("Alice");
        s.sent.clear();
        s.run(29_000L, 500L);
        assertEquals(1, s.loggedCount("renew-unanswered"));
        long renews = s.sent.stream().filter(m -> m.lease() != null && m.lease().op == LeaseMessage.Op.RENEW).count();
        assertTrue("renews=" + renews, renews >= 1 && renews <= 3);
    }

    @Test
    public void revokeOfUnknownLeaseIsRackedUnknown() throws Exception {
        LeaseSupport s = active();
        UUID lease = UUID.randomUUID();
        s.guard.onMessage("Alice", new LeaseMessage(LeaseMessage.Op.REVOKE, lease, "ore1", 3, UUID.randomUUID(),
                0, 30_000, "-"));
        List<LeaseSupport.Msg> racks = s.take(LeaseMessage.TYPE, LeaseMessage.Op.RACK);
        assertEquals(1, racks.size());
        assertTrue(racks.get(0).body.contains("unknown"));
    }

    @Test
    public void staleOfferIgnored() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.guard.onMessage("Alice", new LeaseMessage(LeaseMessage.Op.OFFER, UUID.randomUUID(), "ore1", 0,
                UUID.randomUUID(), 0, 30_000, "-"));
        assertTrue(s.logged("stale-offer"));
    }

    @Test
    public void reconcileRequestReportsEveryLeaseThenEnd() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.guard.onMessage("Alice", new LeaseMessage(LeaseMessage.Op.RREQ, null, "-", 0, null, 0, 0, "-"));
        List<LeaseSupport.Msg> reports = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REPORT);
        assertEquals(2, reports.size());
        assertEquals("end", reports.get(1).lease().extra);
    }

    /**
     * Ostinato: after a coordinator relaunch the RREQ answer carries only leases that still matter. Finished leases
     * the guard remembers (up to KEEP_DONE) used to be reported too, oldest first, on every RREQ resend; over chat
     * that queued ~40 lines/min per bot, delayed the live lease's report past the reconcile timeout and starved the
     * hello replies the relaunched coordinator needs to relearn capabilities.
     */
    @Test
    public void reconcileRequestSkipsFinishedLeases() throws Exception {
        LeaseSupport s = active();
        for (int i = 0; i < 6; i++) {
            String r = s.coord.lease("Bob", "old" + i, "mine iron_ore");
            s.pump();
            UUID id = LeaseSupport.leaseIdOf(r);
            s.coord.revoke("old" + i);
            s.pump();
            s.tick();
            assertEquals(BotLeaseGuard.Phase.DONE, s.guard.phaseOf(id));
        }
        s.advance(BotLeaseGuard.RECONCILE_REPORT_MIN_MS + 1_000L); // They ended well before the coordinator went down.
        UUID live = LeaseSupport.leaseIdOf(s.coord.lease("Bob", "ore1", "mine diamond_ore"));
        s.pump();
        s.sent.clear();
        s.guard.onMessage("Alice", new LeaseMessage(LeaseMessage.Op.RREQ, null, "-", 0, null, 0, 0, "-"));
        List<LeaseSupport.Msg> reports = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REPORT);
        assertEquals(2, reports.size());
        assertEquals(live, reports.get(0).lease().leaseId);
        assertEquals("end", reports.get(1).lease().extra);
    }

    /** Ostinato: a recently finished lease's REPORT must parse; the "canceled (confirmed)" label made it unreadable. */
    @Test
    public void finishedLeaseReportIsReadable() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "old0", "mine iron_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        s.coord.revoke("old0");
        s.pump();
        s.tick();
        assertEquals(BotLeaseGuard.Phase.DONE, s.guard.phaseOf(id));
        s.guard.onMessage("Alice", new LeaseMessage(LeaseMessage.Op.RREQ, null, "-", 0, null, 0, 0, "-"));
        List<LeaseSupport.Msg> reports = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REPORT);
        assertEquals(2, reports.size());
        LeaseMessage rep = reports.get(0).lease();
        assertEquals(id, rep.leaseId);
        assertTrue(rep.extra, rep.extra.contains("exec=canceled,"));
        assertTrue(rep.extra, rep.extra.contains("reason=lease-revoked"));
        assertEquals("end", reports.get(1).lease().extra);
    }

    @Test
    public void wireTokenKeepsTheExtraFieldParseable() {
        assertEquals("stopped_by_Bob_2_", BotLeaseGuard.wireToken("stopped by Bob (2)"));
        assertEquals("a_b_c", BotLeaseGuard.wireToken("a,b=c"));
        assertEquals("none", BotLeaseGuard.wireToken(""));
    }

    /** Chat latency: the OFFER is re-sent several times before the first GRANT arrives, 25 s late. */
    @Test
    public void lateGrantAnswersEarlyRequestUnderChatLatency() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        UUID id = LeaseSupport.leaseIdOf(r);
        List<LeaseSupport.Msg> offers = s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER);
        assertEquals(1, offers.size());
        s.deliver(offers.get(0));
        List<LeaseSupport.Msg> firstReq = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REQ);
        assertEquals(1, firstReq.size());
        // Repeated offers arrive while the first REQ is still in flight; each may re-send REQ (>= 5 s apart).
        for (int i = 0; i < 5; i++) {
            s.advance(5_100L);
            s.deliver(offers.get(0));
        }
        int reqs = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REQ).size();
        assertTrue("reqs=" + reqs, reqs >= 3);
        // Now the first REQ reaches the coordinator; its GRANT answers the oldest nonce.
        s.deliver(firstReq.get(0));
        List<LeaseSupport.Msg> grants = s.take(LeaseMessage.TYPE, LeaseMessage.Op.GRANT);
        assertEquals(1, grants.size());
        s.deliver(grants.get(0));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertEquals(0, s.guard.grantRejectCount());
    }

    /**
     * Ostinato: reproduces the lost GRANT of ore1 gen2. Over chat (D = 60 s) the bot re-sent REQ while the first
     * one was still in flight; the GRANT answering the first REQ arrived after enough re-sends to evict its
     * nonce from a count-based ring, so it was dropped as unknown-nonce and the lease fell to offer-lost.
     */
    @Test
    public void grantForFirstRequestSurvivesManyResendsOverChat() throws Exception {
        LeaseSupport s = active();
        s.chatLink = true;
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        UUID id = LeaseSupport.leaseIdOf(r);
        List<LeaseSupport.Msg> offers = s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER);
        assertEquals(1, offers.size());
        assertEquals(LeaseClock.CHAT_MIN_DURATION_MS, offers.get(0).lease().durationMs);
        s.deliver(offers.get(0));
        List<LeaseSupport.Msg> firstReq = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REQ);
        assertEquals(1, firstReq.size());
        // The OFFER keeps arriving (re-sent and echoed) for 51 s while the first REQ is stuck in a queue.
        for (int i = 0; i < 10; i++) {
            s.advance(5_100L);
            s.deliver(offers.get(0));
        }
        int resends = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REQ).size();
        assertTrue("resends=" + resends, resends > 8); // more than the old ring held
        s.deliver(firstReq.get(0));
        List<LeaseSupport.Msg> grants = s.take(LeaseMessage.TYPE, LeaseMessage.Op.GRANT);
        assertEquals(1, grants.size());
        assertEquals(firstReq.get(0).lease().nonce, grants.get(0).lease().nonce);
        s.deliver(grants.get(0));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertEquals(0, s.guard.grantRejectCount());
    }

    @Test
    public void repeatedOffersThrottleRequests() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        List<LeaseSupport.Msg> offers = s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER);
        for (int i = 0; i < 4; i++) {
            s.deliver(offers.get(0));
            s.advance(1_000L);
        }
        assertEquals(1, s.take(LeaseMessage.TYPE, LeaseMessage.Op.REQ).size());
    }

    @Test
    public void grantPastItsDeadlineIsRejectedAndLoggedOnce() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        UUID id = LeaseSupport.leaseIdOf(r);
        s.deliver(s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER).get(0));
        LeaseSupport.Msg req = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REQ).get(0);
        s.deliver(req);
        LeaseSupport.Msg grant = s.take(LeaseMessage.TYPE, LeaseMessage.Op.GRANT).get(0);
        s.advance(31_000L); // the GRANT sat in a queue for longer than D
        s.deliver(grant);
        s.deliver(grant);
        assertEquals(BotLeaseGuard.Phase.PENDING, s.guard.phaseOf(id));
        assertEquals(2, s.guard.grantRejectCount());
        assertEquals(1, s.loggedCount("grant-rejected lease=" + id));
        assertTrue(s.logged("reason=late"));
    }

    private static int racks(List<LeaseSupport.Msg> q) {
        int n = 0;
        for (LeaseSupport.Msg m : q) {
            LeaseMessage lm = m.lease();
            if (lm != null && lm.op == LeaseMessage.Op.RACK) {
                n++;
            }
        }
        return n;
    }

    /** Ostinato: REVOKEs that cross our RACK in flight get no second RACK; one after the gap still does. */
    @Test
    public void repeatedRevokesGetOneRackPerGap() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        long gen = s.coord.record(id).gen;
        UUID cmd = s.coord.record(id).commandId;
        s.coord.revoke("ore1");
        s.pump();
        s.tick();
        assertEquals(LeaseRecord.State.RELEASED, s.coord.record(id).state);
        LeaseMessage revoke = new LeaseMessage(LeaseMessage.Op.REVOKE, id, "ore1", gen, cmd, 0, 0, "-");
        for (int i = 0; i < 4; i++) {
            s.guard.onMessage("Alice", revoke);
            s.advance(500L);
        }
        assertEquals(0, racks(s.take(LeaseMessage.TYPE, LeaseMessage.Op.RACK)));
        assertTrue(s.guard.racksSuppressed() >= 4);
        s.advance(LeaseClock.repeatGap(30_000L));
        s.guard.onMessage("Alice", revoke);
        assertEquals(1, racks(s.take(LeaseMessage.TYPE, LeaseMessage.Op.RACK)));
        // Unknown lease (a relaunch): one RACK per gap as well.
        LeaseMessage unknown = new LeaseMessage(LeaseMessage.Op.REVOKE, UUID.randomUUID(), "ore1", 3, null, 0, 0, "-");
        for (int i = 0; i < 3; i++) {
            s.guard.onMessage("Alice", unknown);
        }
        assertEquals(1, racks(s.take(LeaseMessage.TYPE, LeaseMessage.Op.RACK)));
        s.advance(BotLeaseGuard.UNKNOWN_RACK_GAP_MS);
        s.guard.onMessage("Alice", unknown);
        assertEquals(1, racks(s.take(LeaseMessage.TYPE, LeaseMessage.Op.RACK)));
    }
}
