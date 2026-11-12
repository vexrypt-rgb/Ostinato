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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Ostinato: Phase 2a coordinator behaviour, end to end with a guarded bot. */
public class LeaseCoordinatorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LeaseSupport active() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        return s;
    }

    @Test
    public void grantStartsCommandOnce() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        assertTrue(r, r.contains("offered ore1 gen 1"));
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        assertEquals(LeaseRecord.State.GRANTED, s.coord.record(id).state);
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertEquals(1, s.bobExec.count("mine"));
        s.run(40_000L, 1_000L);
        assertEquals(1, s.bobExec.count("mine"));
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
    }

    @Test
    public void refusesWhenNotDesignatedAndCountsRivals() throws Exception {
        LeaseSupport s = active();
        LeaseCoordinator other = s.newCoordinator("Bob", "Alice");
        assertEquals(LeaseCoordinator.Mode.REFUSED, other.start());
        assertTrue(s.logged("swarm-lease: refused coordinator=Bob"));
        assertTrue(other.lease("Alice", "ore1", "mine x").contains("refused"));
        s.coord.onMessage("Mallory", new LeaseMessage(LeaseMessage.Op.GRANT, UUID.randomUUID(), "ore1", 1,
                UUID.randomUUID(), 1, 30_000, "-"));
        assertEquals(1, s.coord.rivalOps());
    }

    @Test
    public void botIgnoresNonDesignatedSource() throws Exception {
        LeaseSupport s = active();
        s.guard.onMessage("Mallory", new LeaseMessage(LeaseMessage.Op.OFFER, UUID.randomUUID(), "ore1", 1,
                UUID.randomUUID(), 0, 30_000, "-"));
        s.guard.onMessage("Mallory", new LeaseMessage(LeaseMessage.Op.OFFER, UUID.randomUUID(), "ore1", 2,
                UUID.randomUUID(), 0, 30_000, "-"));
        assertEquals(2, s.guard.ignoredCount());
        assertEquals(1, s.loggedCount("swarm-lease: ignored offer from Mallory"));
        assertTrue(s.queue.isEmpty());
    }

    @Test
    public void grantHeldWhileReconciling() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        // Coordinator restarts: a fresh instance on the same store.
        s.coord = s.newCoordinator("Alice", "Alice");
        s.dropTo.add("Bob"); // the RREQ is lost, so the bot never reports
        s.coord.start();
        assertEquals(LeaseCoordinator.Mode.RECONCILING, s.coord.mode());
        s.pump();
        s.dropTo.clear();
        s.coord.onMessage("Bob", new LeaseMessage(LeaseMessage.Op.RENEW, id, "ore1", 1, s.coord.record(id).commandId,
                s.mono.get(), 30_000, "-"));
        assertTrue(s.logged("grant-held"));
        assertTrue(s.take(LeaseMessage.TYPE, LeaseMessage.Op.GRANT).isEmpty());
        assertTrue(s.coord.lease("Bob", "ore2", "mine x").contains("reconciling"));
        s.run(40_000L, 1_000L);
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
    }

    @Test
    public void reconcileFinishesEarlyWhenBotReports() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.coord = s.newCoordinator("Alice", "Alice");
        s.coord.start();
        s.pump();
        assertEquals(LeaseCoordinator.Mode.RECONCILING, s.coord.mode());
        s.advance(6_100L);
        s.tick();
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
    }

    // Ostinato: live 2026-11-12 (b' on chat) - the holder's report reached the restarted coordinator after 2*M, so a
    // lease that had simply run out was booked as reconcile-timeout instead of what the bot reported.
    @Test
    public void chatHolderReportAfterTwoMarginsIsStillCounted() throws Exception {
        LeaseSupport s = active();
        s.chatLink = true;
        UUID id = LeaseSupport.leaseIdOf(s.coord.lease("Bob", "ore1", "mine diamond_ore"));
        s.pump();
        // The coordinator is down long enough for the lease to run out; Bob fences itself, nobody hears it.
        s.advance(70_000L);
        s.dropTo.add("Alice");
        s.guard.tick();
        s.pump();
        s.dropTo.clear();
        s.coord = s.newCoordinator("Alice", "Alice");
        s.coord.start();
        List<LeaseSupport.Msg> slow = s.take(LeaseMessage.TYPE, LeaseMessage.Op.RREQ); // still crossing chat
        assertFalse(slow.isEmpty());
        s.advance(7_000L);
        s.coord.tick();
        assertEquals(LeaseCoordinator.Mode.RECONCILING, s.coord.mode());
        for (LeaseSupport.Msg m : slow) {
            s.deliver(m);
        }
        s.pump();
        s.advance(1_000L);
        s.coord.tick();
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        assertEquals("lease-expired", s.coord.record(id).endReason); // the bot's own report, not a timeout
    }

    // Ostinato: live 2026-11-12 (b5) - the coordinator came back 16 s before the holder's deadline and kept the
    // lease, but the holder's renew backoff had already put its next renew past the deadline, so it fenced anyway.
    @Test
    public void keptLeaseIsRenewedBeforeTheHoldersDeadline() throws Exception {
        LeaseSupport s = active();
        s.chatLink = true;
        UUID id = LeaseSupport.leaseIdOf(s.coord.lease("Bob", "ore1", "mine diamond_ore"));
        s.pump();
        s.run(10_000L, 1_000L);
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        s.dropTo.add("Alice"); // the coordinator's client dies
        for (int i = 0; i < 34; i++) {
            s.advance(1_000L);
            s.guard.tick();
            s.pump();
        }
        s.dropTo.clear();
        s.coord = s.newCoordinator("Alice", "Alice");
        s.coord.start();
        s.pump();
        s.run(25_000L, 1_000L); // well past the deadline the holder had when the coordinator died
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
    }

    // Ostinato: live 2026-11-12 (b7) - after the restart the coordinator kept the lease, then re-submitted the bound
    // command on the first renew; caps were not relearned yet, the submit was refused and the lease was revoked.
    @Test
    public void keptLeaseDoesNotResubmitItsCommandAfterRestart() throws Exception {
        LeaseSupport s = active();
        UUID id = LeaseSupport.leaseIdOf(s.coord.lease("Bob", "ore1", "mine diamond_ore"));
        s.pump();
        s.run(5_000L, 1_000L);
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        int mines = s.bobExec.count("mine");
        s.coord = s.newCoordinator("Alice", "Alice");
        s.refuseAfterGrant = true; // any submit now is refused, as with caps unknown after a relaunch
        s.coord.start();
        s.pump();
        s.run(60_000L, 1_000L);
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        assertFalse(s.logged("reason=command-refused"));
        assertEquals(LeaseRecord.State.GRANTED, s.coord.record(id).state);
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
        assertEquals(mines, s.bobExec.count("mine"));
    }

    @Test
    public void chatHolderThatNeverReportsStillTimesOut() throws Exception {
        LeaseSupport s = active();
        s.chatLink = true;
        UUID id = LeaseSupport.leaseIdOf(s.coord.lease("Bob", "ore1", "mine diamond_ore"));
        s.pump();
        s.advance(70_000L);
        s.coord = s.newCoordinator("Alice", "Alice");
        s.dropTo.add("Bob");
        s.coord.start();
        s.pump();
        s.run(21_000L, 1_000L);
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        assertEquals("reconcile-timeout", s.coord.record(id).endReason);
    }

    @Test
    public void noReplacementBeforeExpiryOrRack() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        assertTrue(s.coord.lease("Bob", "ore1", "mine x").contains("busy"));
        s.dropTo.add("Alice"); // RACK will be lost
        assertTrue(s.coord.revoke("ore1").contains("revoking"));
        s.pump();
        assertEquals(LeaseRecord.State.REVOKING, s.coord.record(id).state);
        s.run(30_000L, 1_000L);
        assertTrue("still within D+M", s.coord.lease("Bob", "ore1", "mine x").contains("busy"));
        s.run(4_000L, 1_000L);
        assertEquals(LeaseRecord.State.EXPIRED, s.coord.record(id).state);
        assertEquals("revoke-unacked", s.coord.record(id).endReason);
        String again = s.coord.lease("Bob", "ore1", "mine x");
        assertTrue(again, again.contains("gen 2"));
    }

    @Test
    public void rackFreesResourceImmediately() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.coord.revoke("ore1");
        s.pump();
        s.tick();
        UUID id = LeaseSupport.leaseIdOf(r);
        assertEquals(LeaseRecord.State.RELEASED, s.coord.record(id).state);
        assertFalse(s.stopCalls.isEmpty());
        assertTrue(s.coord.lease("Bob", "ore1", "mine x").contains("gen 2"));
    }

    @Test
    public void expiryOutcomeWithoutConfirmedStopIsUnknown() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        s.dropTo.add("Alice");
        s.dropTo.add("Bob");
        s.run(40_000L, 1_000L);
        LeaseRecord rec = s.coord.record(id);
        assertEquals(LeaseRecord.State.EXPIRED, rec.state);
        assertEquals("lease-expired", rec.endReason);
        assertNotEquals(ExecutionState.SUCCEEDED, rec.exec);
        assertNotEquals(ExecutionState.FAILED, rec.exec);
    }

    @Test
    public void staleGenerationAfterRelaunchDoesNotResume() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        UUID cmd = s.coord.record(id).commandId;
        s.relaunchBob();
        s.bobExec.byName.clear();
        // A stale RENEW from the old generation is refused.
        s.coord.onMessage("Bob", new LeaseMessage(LeaseMessage.Op.RENEW, id, "ore1", 0, cmd, s.mono.get(),
                30_000, "-"));
        s.pump();
        // Revoke from the coordinator reaches the relaunched bot: RACK unknown.
        s.coord.revoke("ore1");
        s.pump();
        assertTrue(s.logged("unknown"));
        s.run(5_000L, 500L);
        assertEquals(0, s.bobExec.count("mine"));
    }

    @Test
    public void refusedCommandAfterGrantRevokesLease() throws Exception {
        LeaseSupport s = active();
        s.refuseAfterGrant = true;
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.tick();
        UUID id = LeaseSupport.leaseIdOf(r);
        assertTrue(s.logged("reason=command-refused"));
        assertEquals("command-refused", s.coord.record(id).endReason);
        assertEquals(LeaseRecord.State.RELEASED, s.coord.record(id).state); // bot racked
        assertEquals(0, s.bobExec.count("mine"));
        s.refuseAfterGrant = false;
        assertTrue(s.coord.lease("Bob", "ore1", "mine x").contains("gen 2"));
    }

    @Test
    public void noOfferWhileMineObservationUnknown() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.aliceP.onPeerDisconnect("Bob"); // the earlier mine's outcome is now unknown
        String r = s.coord.lease("Bob", "ore2", "mine diamond_ore");
        assertTrue(r, r.contains("not offering"));
        assertTrue(s.logged("not-offered resource=ore2"));
        assertTrue(s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER).isEmpty());
    }

    /**
     * Ostinato: live kill check, 2026-11-12. The holder died mid-mine, its lease expired, and the coordinator still
     * refused every replacement because the dead holder's mine stayed observation-unknown. Expiry plus margin is the
     * fence, so after it the replacement is offered at gen+1; before it the answer is "busy".
     */
    @Test
    public void replacementAfterHolderDiesIsOfferedOnceTheFencePasses() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        assertEquals(LeaseRecord.State.GRANTED, s.coord.record(id).state);
        s.dropTo.add("Bob"); // the holder's client is gone: nothing reaches it, nothing comes back
        s.dropTo.add("Alice");
        s.aliceP.onPeerDisconnect("Bob"); // its mine is now observation-unknown
        String early = s.coord.lease("Bob", "ore1", "mine iron_ore");
        assertTrue(early, early.contains("busy") && early.contains("gen 1"));
        s.run(30_000L, 1_000L);
        String inside = s.coord.lease("Bob", "ore1", "mine iron_ore");
        assertTrue("within D+M: " + inside, inside.contains("busy"));
        s.run(4_000L, 1_000L);
        assertEquals(LeaseRecord.State.EXPIRED, s.coord.record(id).state);
        s.relaunchBob();
        s.bobExec.byName.clear();
        s.dropTo.clear();
        String again = s.coord.lease("Bob", "ore1", "mine iron_ore");
        assertTrue(again, again.contains("gen 2"));
        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
    }

    /** Ostinato: the fence only frees leased work; an unleased mine with an unknown outcome still blocks. */
    @Test
    public void unleasedUnknownMineStillBlocksAfterAnExpiry() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.dropTo.add("Bob");
        s.dropTo.add("Alice");
        s.aliceP.submit("Bob", "mine coal_ore"); // unleased, never answered
        s.aliceP.onPeerDisconnect("Bob");
        s.run(34_000L, 1_000L);
        assertEquals(LeaseRecord.State.EXPIRED, s.coord.record(LeaseSupport.leaseIdOf(r)).state);
        String again = s.coord.lease("Bob", "ore1", "mine iron_ore");
        assertTrue(again, again.contains("not offering") && again.contains("observation is unknown"));
    }

    @Test
    public void noOfferForCommandThatWouldNotSend() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "t1", "tunnel");
        assertTrue(r, r.contains("not offering") && r.contains("missing arguments"));
        assertTrue(s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER).isEmpty());
        assertTrue(s.coord.lease("Bob", "t1", "mine diamond_ore").contains("gen 1"));
    }

    private static int count(List<LeaseSupport.Msg> sent, LeaseMessage.Op op, int from) {
        int n = 0;
        for (int i = from; i < sent.size(); i++) {
            LeaseMessage m = sent.get(i).lease();
            if (m != null && m.op == op) {
                n++;
            }
        }
        return n;
    }

    /** Ostinato: REQ resends that cross a REVOKE in flight get one REVOKE per repeat gap, not one each. */
    @Test
    public void requestsWhileRevokingGetOneRevokePerGap() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        LeaseRecord rec = s.coord.record(id);
        s.dropTo.add("Bob");
        s.coord.revoke("ore1");
        s.pump();
        int mark = s.sent.size();
        LeaseMessage req = new LeaseMessage(LeaseMessage.Op.REQ, id, "ore1", rec.gen, rec.commandId, 7L, 0L, "-");
        for (int i = 0; i < 4; i++) {
            s.coord.onMessage("Bob", req);
            s.advance(1_000L);
        }
        assertEquals("no REVOKE beyond the one just sent", 0, count(s.sent, LeaseMessage.Op.REVOKE, mark));
        s.advance(LeaseClock.repeatGap(30_000L));
        s.coord.onMessage("Bob", req);
        assertEquals(1, count(s.sent, LeaseMessage.Op.REVOKE, mark));
        assertTrue(s.coord.repliesSuppressed() >= 4);
        // Stale generation: one denial, then quiet until the gap passes.
        mark = s.sent.size();
        LeaseMessage stale = new LeaseMessage(LeaseMessage.Op.REQ, UUID.randomUUID(), "ore1", 9, UUID.randomUUID(),
                1L, 0L, "-");
        for (int i = 0; i < 5; i++) {
            s.coord.onMessage("Bob", stale);
        }
        assertEquals(1, count(s.sent, LeaseMessage.Op.REVOKE, mark));
        s.advance(LeaseClock.repeatGap(30_000L));
        s.coord.onMessage("Bob", stale);
        assertEquals(2, count(s.sent, LeaseMessage.Op.REVOKE, mark));
    }
}
