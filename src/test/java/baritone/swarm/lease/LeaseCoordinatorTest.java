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

    @Test
    public void noOfferForCommandThatWouldNotSend() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "t1", "tunnel");
        assertTrue(r, r.contains("not offering") && r.contains("missing arguments"));
        assertTrue(s.take(LeaseMessage.TYPE, LeaseMessage.Op.OFFER).isEmpty());
        assertTrue(s.coord.lease("Bob", "t1", "mine diamond_ore").contains("gen 1"));
    }
}
