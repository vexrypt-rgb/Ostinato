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

    // ---- Ostinato: early renew while reconciling (live b8r3: a reported holder's renew sat in heldRequests) ----

    private static final String CLAIM = "phase=valid,exec=running,obs=running,reason=none,active=true";

    /** A restarted coordinator still reconciling: Bob has reported his ore1 lease, Carol (store only) has not. */
    private static final class Restarted {
        LeaseSupport s;
        UUID bob;
        UUID carol;
        int since;

        UUID cmd(UUID lease) {
            return s.coord.record(lease).commandId;
        }

        void from(String bot, LeaseMessage.Op op, UUID lease, String resource, long gen, UUID cmd) {
            s.coord.onMessage(bot, new LeaseMessage(op, lease, resource, gen, cmd, s.mono.get(), 30_000, "-"));
            s.pump();
        }

        void claim(String bot, UUID lease, String resource, long gen, UUID cmd) {
            claim(bot, lease, resource, gen, cmd, CLAIM);
        }

        void claim(String bot, UUID lease, String resource, long gen, UUID cmd, String extra) {
            s.coord.onMessage(bot, new LeaseMessage(LeaseMessage.Op.REPORT, lease, resource, gen, cmd, 0, 0, extra));
        }

        /** Ostinato: control for the claim tests - Carol re-sends a correct claim and her renew now goes out early. */
        void carolCorrectClaimIsRenewed() {
            claim("Carol", carol, "ore9", 1, cmd(carol));
            from("Carol", LeaseMessage.Op.RENEW, carol, "ore9", 1, cmd(carol));
            assertEquals(1, grants(carol).size());
            assertTrue(s.logged("renewed-early lease=" + carol));
        }

        void end(String bot) {
            s.coord.onMessage(bot, new LeaseMessage(LeaseMessage.Op.REPORT, null, "-", 0, null, 0, 0, "end"));
            s.pump();
        }

        List<LeaseSupport.Msg> grants(UUID lease) {
            List<LeaseSupport.Msg> out = new java.util.ArrayList<>();
            for (LeaseSupport.Msg m : s.sentSince(since, "Alice", LeaseMessage.Op.GRANT)) {
                if (lease.equals(m.lease().leaseId)) {
                    out.add(m);
                }
            }
            return out;
        }

        void assertNotRenewedEarly(UUID lease) {
            assertEquals(LeaseCoordinator.Mode.RECONCILING, s.coord.mode());
            assertEquals(0, grants(lease).size());
            assertFalse(s.logged("renewed-early lease=" + lease));
            assertEquals(0, s.coord.earlyRenewals());
        }
    }

    private Restarted restarted(boolean pumpReports, java.util.function.Consumer<LeaseSupport> beforeRestart)
            throws Exception {
        Restarted r = new Restarted();
        LeaseSupport s = active();
        r.s = s;
        r.bob = LeaseSupport.leaseIdOf(s.coord.lease("Bob", "ore1", "mine diamond_ore"));
        s.pump();
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(r.bob));
        assertEquals(1, s.bobExec.count("mine"));
        r.carol = s.addHolder("Carol", "ore9", 1, LeaseRecord.State.GRANTED, s.wall.get() + 20_000L);
        beforeRestart.accept(s);
        r.since = s.sent.size();
        s.coord = s.newCoordinator("Alice", "Alice");
        s.coord.start();
        if (pumpReports) {
            s.pump();
            assertTrue(s.logged("reconcile-report bot=Bob"));
        }
        assertEquals(LeaseCoordinator.Mode.RECONCILING, s.coord.mode());
        return r;
    }

    private Restarted restarted() throws Exception {
        return restarted(true, s -> { });
    }

    @Test
    public void reportedHolderIsRenewedWhileOthersStillReconcile() throws Exception {
        Restarted r = restarted();
        LeaseSupport s = r.s;
        s.tick(); // Bob's renew goes out right behind his report
        assertEquals(LeaseCoordinator.Mode.RECONCILING, s.coord.mode());
        assertTrue(s.logged("swarm-lease: renewed-early lease=" + r.bob + " gen=1 bot=Bob (reconciling)"));
        assertEquals(1, s.coord.earlyRenewals());
        assertEquals(1, r.grants(r.bob).size());
        assertTrue(s.logged("bot=self after-reconcile deadline in"));
        assertEquals(1, s.bobExec.count("mine")); // a renew, never a second submit
        assertEquals(0, r.grants(r.carol).size());
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(r.bob));
    }

    @Test
    public void renewHeldBeforeTheReportIsGrantedWhenTheReportEnds() throws Exception {
        Restarted r = restarted(false, s -> { });
        LeaseSupport s = r.s;
        for (LeaseSupport.Msg m : s.take(LeaseMessage.TYPE, LeaseMessage.Op.RREQ)) {
            s.deliver(m); // Bob answers; his reports stay queued (slow chat)
        }
        List<LeaseSupport.Msg> reports = s.take(LeaseMessage.TYPE, LeaseMessage.Op.REPORT);
        assertFalse(reports.isEmpty());
        s.tick(); // the renew overtakes the reports
        assertTrue(s.logged("grant-held"));
        assertEquals(0, r.grants(r.bob).size());
        for (LeaseSupport.Msg m : reports) {
            s.deliver(m);
        }
        s.pump();
        assertEquals(LeaseCoordinator.Mode.RECONCILING, s.coord.mode());
        assertEquals(1, r.grants(r.bob).size());
        assertEquals(1, s.coord.earlyRenewals());
        assertTrue(s.logged("bot=self after-reconcile deadline in"));
    }

    // Ostinato: the margin is measured on the bot, the time left on its lease when the first renew lands.
    @Test
    public void afterReconcileLogShowsTheMeasuredTimeLeft() throws Exception {
        Restarted r = restarted();
        LeaseSupport s = r.s;
        s.advance(4_000L);
        s.pump();
        String d = s.guard.describe().get(0);
        long left = Long.parseLong(d.substring(d.indexOf("left=") + 5, d.indexOf("ms", d.indexOf("left="))));
        s.tick();
        assertTrue(s.log.toString(), s.logged("after-reconcile deadline in " + left + " ms (unanswered=0)"));
        assertTrue(left > 0 && left < 30_000L);
        s.run(20_000L, 1_000L);
        assertEquals(1, s.loggedCount("after-reconcile")); // only the first renew after the RREQ
    }

    @Test
    public void reconcileTrafficIsUrgentAndLaterRenewsAreNot() throws Exception {
        Restarted r = restarted();
        LeaseSupport s = r.s;
        s.tick();
        List<LeaseSupport.Msg> rreq = s.sentSince(r.since, "Alice", LeaseMessage.Op.RREQ);
        List<LeaseSupport.Msg> reports = s.sentSince(r.since, "Bob", LeaseMessage.Op.REPORT);
        List<LeaseSupport.Msg> renews = s.sentSince(r.since, "Bob", LeaseMessage.Op.RENEW);
        assertFalse(rreq.isEmpty());
        assertTrue(reports.size() >= 2); // the claim and the end marker
        assertEquals(1, renews.size());
        for (LeaseSupport.Msg m : rreq) {
            assertTrue(m.toString(), m.urgent);
        }
        for (LeaseSupport.Msg m : reports) {
            assertTrue(m.toString(), m.urgent);
        }
        assertTrue(renews.get(0).urgent);
        assertTrue(r.grants(r.bob).get(0).urgent);
        for (int i = 0; i < 40 && s.coord.mode() != LeaseCoordinator.Mode.ACTIVE; i++) {
            s.run(1_000L, 1_000L); // Carol's notBefore passes: reconciliation ends
        }
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        int mark = s.sent.size();
        s.run(30_000L, 1_000L);
        List<LeaseSupport.Msg> later = s.sentSince(mark, "Bob", LeaseMessage.Op.RENEW);
        assertTrue(later.size() >= 4);
        for (LeaseSupport.Msg m : later) {
            assertFalse(m.toString(), m.urgent);
        }
        for (LeaseSupport.Msg m : s.sentSince(mark, "Alice", LeaseMessage.Op.GRANT)) {
            assertFalse(m.toString(), m.urgent);
        }
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(r.bob));
        assertEquals(1, s.bobExec.count("mine"));
    }

    // Ostinato: live b9r1 - the reconcile renew landed just after reconciliation ended and its GRANT queued HIGH.
    @Test
    public void reconcileRenewArrivingAfterReconcileIsStillUrgent() throws Exception {
        Restarted r = restarted();
        LeaseSupport s = r.s;
        s.guard.tick(); // Bob's reconcile renew, behind his report
        List<LeaseSupport.Msg> inFlight = s.take(LeaseMessage.TYPE, LeaseMessage.Op.RENEW);
        assertEquals(1, inFlight.size());
        assertTrue(inFlight.get(0).urgent);
        for (int i = 0; i < 40 && s.coord.mode() != LeaseCoordinator.Mode.ACTIVE; i++) {
            s.advance(1_000L);
            s.coord.tick(); // the renew is still in flight (slow chat) when reconciliation ends
            s.pump();
        }
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        assertEquals(0, r.grants(r.bob).size());
        for (LeaseSupport.Msg m : inFlight) {
            s.deliver(m);
        }
        s.pump();
        List<LeaseSupport.Msg> g = r.grants(r.bob);
        assertEquals(1, g.size());
        assertTrue(g.get(0).toString(), g.get(0).urgent);
        assertTrue(s.logged("bot=self after-reconcile deadline in"));
        assertEquals(1, s.bobExec.count("mine")); // a renew, never a second submit
        int mark = s.sent.size();
        s.run(15_000L, 1_000L);
        List<LeaseSupport.Msg> later = s.sentSince(mark, "Alice", LeaseMessage.Op.GRANT);
        assertFalse(later.isEmpty());
        for (LeaseSupport.Msg m : later) {
            assertFalse(m.toString(), m.urgent); // only the first renew after the relaunch
        }
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(r.bob));
    }

    // ---- the safety rules: exactly one thing wrong each time, and nothing is renewed early ----

    @Test
    public void unknownLeaseIsNeverRenewedEarly() throws Exception {
        Restarted r = restarted();
        UUID ghost = UUID.randomUUID();
        r.claim("Bob", ghost, "ore1", 1, r.cmd(r.bob));
        r.from("Bob", LeaseMessage.Op.RENEW, ghost, "ore1", 1, r.cmd(r.bob));
        r.assertNotRenewedEarly(ghost);
        assertEquals(0, r.s.coord.earlyRenewals());
    }

    @Test
    public void wrongGenerationIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.from("Bob", LeaseMessage.Op.RENEW, r.bob, "ore1", 2, r.cmd(r.bob));
        r.assertNotRenewedEarly(r.bob);
        r.from("Bob", LeaseMessage.Op.RENEW, r.bob, "ore1", 1, r.cmd(r.bob)); // control: the right gen is
        assertEquals(1, r.grants(r.bob).size());
    }

    @Test
    public void anotherBotsLeaseIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.claim("Carol", r.carol, "ore9", 1, r.cmd(r.carol));
        r.end("Carol");
        r.from("Bob", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol)); // control: its holder is
        assertEquals(1, r.grants(r.carol).size());
    }

    @Test
    public void offeredLeaseIsNotRenewedEarly() throws Exception {
        UUID[] offered = new UUID[1];
        Restarted r = restarted(true, s -> {
            try {
                offered[0] = s.addHolder("Bob", "ore5", 1, LeaseRecord.State.OFFERED, s.wall.get() + 20_000L);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        r.claim("Bob", offered[0], "ore5", 1, r.cmd(offered[0]));
        r.from("Bob", LeaseMessage.Op.RENEW, offered[0], "ore5", 1, r.cmd(offered[0]));
        r.assertNotRenewedEarly(offered[0]);
        assertEquals(LeaseRecord.State.OFFERED, r.s.coord.record(offered[0]).state);
    }

    @Test
    public void requestIsNeverGrantedWhileReconciling() throws Exception {
        Restarted r = restarted();
        r.from("Bob", LeaseMessage.Op.REQ, r.bob, "ore1", 1, r.cmd(r.bob));
        r.assertNotRenewedEarly(r.bob);
        assertTrue(r.s.logged("grant-held"));
    }

    @Test
    public void staleRecordBelowHighWaterIsNotRenewedEarly() throws Exception {
        Restarted r = restarted(true, s -> {
            try {
                s.setHighWater("ore1", 2);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        r.from("Bob", LeaseMessage.Op.RENEW, r.bob, "ore1", 1, r.cmd(r.bob));
        r.assertNotRenewedEarly(r.bob);
    }

    @Test
    public void competingClaimOnTheResourceBlocksEarlyRenew() throws Exception {
        Restarted r = restarted();
        r.claim("Carol", UUID.randomUUID(), "ore1", 1, UUID.randomUUID());
        r.from("Bob", LeaseMessage.Op.RENEW, r.bob, "ore1", 1, r.cmd(r.bob));
        r.assertNotRenewedEarly(r.bob);
    }

    @Test
    public void claimWithoutEndOfReportWaitsForTheEnd() throws Exception {
        Restarted r = restarted();
        r.claim("Carol", r.carol, "ore9", 1, r.cmd(r.carol));
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
        assertTrue(r.s.logged("grant-held"));
        r.end("Carol"); // control: the held renew goes out once her report is complete
        assertEquals(1, r.grants(r.carol).size());
        assertTrue(r.s.logged("renewed-early lease=" + r.carol));
    }

    @Test
    public void reportWithoutClaimIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.end("Carol");
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
    }

    @Test
    public void wrongCommandIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.from("Bob", LeaseMessage.Op.RENEW, r.bob, "ore1", 1, UUID.randomUUID());
        r.assertNotRenewedEarly(r.bob);
        r.from("Bob", LeaseMessage.Op.RENEW, r.bob, "ore1", 1, r.cmd(r.bob)); // control: the right command is
        assertEquals(1, r.grants(r.bob).size());
    }

    // Ostinato: the claim must come from the holder itself, at the lease's generation and command, valid and doing
    // something. Each test breaks one of those and nothing else; the control then sends the correct claim.
    @Test
    public void claimByAnotherBotIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.claim("Bob", r.carol, "ore9", 1, r.cmd(r.carol));
        r.end("Carol");
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
        r.carolCorrectClaimIsRenewed();
    }

    @Test
    public void claimAtAnotherGenerationIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.claim("Carol", r.carol, "ore9", 2, r.cmd(r.carol));
        r.end("Carol");
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
        r.carolCorrectClaimIsRenewed();
    }

    @Test
    public void claimWithAnotherCommandIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.claim("Carol", r.carol, "ore9", 1, UUID.randomUUID());
        r.end("Carol");
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
        r.carolCorrectClaimIsRenewed();
    }

    @Test
    public void expiredClaimIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.claim("Carol", r.carol, "ore9", 1, r.cmd(r.carol), "phase=expired,exec=running,obs=running,reason=none,active=true");
        r.end("Carol");
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
        r.carolCorrectClaimIsRenewed();
    }

    @Test
    public void idleClaimIsNotRenewedEarly() throws Exception {
        Restarted r = restarted();
        r.claim("Carol", r.carol, "ore9", 1, r.cmd(r.carol), "phase=valid,exec=none,obs=none,reason=none,active=false");
        r.end("Carol");
        r.from("Carol", LeaseMessage.Op.RENEW, r.carol, "ore9", 1, r.cmd(r.carol));
        r.assertNotRenewedEarly(r.carol);
        r.carolCorrectClaimIsRenewed();
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
