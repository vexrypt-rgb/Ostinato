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
}
