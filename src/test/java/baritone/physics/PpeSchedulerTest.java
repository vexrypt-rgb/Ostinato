/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.BodyState;
import baritone.api.physics.PhysicsTelemetry;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionRequest.Priority;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionResult.Assumption;
import baritone.api.physics.PredictionResult.Status;
import baritone.api.physics.PredictionTicket;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: the scheduler's queueing, sharing, caching, deadlines and cancellation, run by hand (no worker thread). */
public class PpeSchedulerTest {
    private static final Terrain FLAT = PpeWorlds.flat(PpeWorlds.STONE);

    private static PredictionRequest.Builder fall(double y) {
        return PredictionRequest.fall(BodyState.builder(BodyState.Type.PLAYER).pos(0.5, y, 0.5).build(), 200);
    }

    private static PredictionScheduler scheduler(int capacity) {
        return new PredictionScheduler(capacity, new PredictionCache(16), new PhysicsStats());
    }

    @Test
    public void equalQuestionsShareOneJobThenHitTheCache() throws InterruptedException {
        PredictionScheduler s = scheduler(8);
        PredictionTicket a = s.submit(fall(80).build(), FLAT), b = s.submit(fall(80).consumer("other").build(), FLAT);
        assertEquals(1, s.queueDepth());
        assertTrue(s.runNext(0));
        assertFalse(s.runNext(0));
        assertSame(a.result(), a.result());
        assertEquals(Status.OK, a.result().status);
        assertEquals(a.result().landing.damage, b.result().landing.damage, 0);
        PredictionTicket c = s.submit(fall(80).build(), FLAT);
        assertTrue(c.isDone());
        assertEquals(a.result().landing.tick, c.result().landing.tick);
        PredictionResult inline = s.predict(fall(80).build(), FLAT);
        assertEquals(a.result().y, inline.y, 0);
        PhysicsTelemetry t = s.telemetry();
        assertEquals(4, t.requests());
        assertEquals(1, t.dedupHits());
        assertEquals(2, t.cacheHits());
        assertEquals(1, t.completed());
    }

    @Test
    public void changedTerrainMissesTheCache() throws InterruptedException {
        PredictionScheduler s = scheduler(8);
        s.submit(fall(80).build(), FLAT);
        s.runNext(0);
        PredictionTicket t = s.submit(fall(80).build(), PpeWorlds.flat(PpeWorlds.HAY));
        assertFalse(t.isDone());
        s.runNext(0);
        assertEquals(2, t.result().landing.damage, 0); // floor(16 - 3) = 13 on stone; floor(13 * 0.2) = 2 on hay
    }

    @Test
    public void mostUrgentRunsFirstAndFullQueueDropsTheLeastUrgent() throws InterruptedException {
        PredictionScheduler s = scheduler(2);
        PredictionTicket plan = s.submit(fall(70).priority(Priority.PLANNING).build(), FLAT);
        PredictionTicket move = s.submit(fall(71).priority(Priority.MOVEMENT).build(), FLAT);
        PredictionTicket plan2 = s.submit(fall(72).priority(Priority.PLANNING).build(), FLAT);
        assertEquals(Status.REJECTED, plan2.result().status); // full, and not more urgent than anything queued
        PredictionTicket emergency = s.submit(fall(73).priority(Priority.EMERGENCY).build(), FLAT);
        assertEquals(Status.REJECTED, plan.result().status); // dropped for the emergency
        assertFalse(emergency.isDone());
        s.runNext(0);
        assertTrue(emergency.isDone());
        assertFalse(move.isDone());
        s.runNext(0);
        assertTrue(move.isDone());
        assertEquals(2, s.telemetry().rejected());
        assertEquals(2, s.telemetry().queueHighWater());
    }

    @Test
    public void expiredAndCancelled() throws InterruptedException {
        PredictionScheduler s = scheduler(8);
        PredictionTicket late = s.submit(fall(80).deadlineIn(50_000_000).build(), FLAT);
        PredictionTicket dropped = s.submit(fall(81).build(), FLAT);
        Thread.sleep(60);
        dropped.cancel();
        assertEquals(Status.CANCELLED, dropped.result().status);
        assertEquals(1, s.queueDepth());
        s.runNext(0);
        assertEquals(Status.EXPIRED, late.result().status);
        assertEquals(PredictionResult.Confidence.UNKNOWN, late.result().confidence);
        // one of two sharers cancelling leaves the job for the other
        PredictionTicket a = s.submit(fall(90).build(), FLAT), b = s.submit(fall(90).build(), FLAT);
        a.cancel();
        assertEquals(1, s.queueDepth());
        s.runNext(0);
        assertEquals(Status.OK, b.result().status);
        assertEquals(Status.CANCELLED, a.result().status);
    }

    @Test
    public void loadDegradesOnlyTheLessUrgent() throws InterruptedException {
        PredictionScheduler s = scheduler(4);
        List<PredictionTicket> ts = new ArrayList<>();
        ts.add(s.submit(fall(80).priority(Priority.COMBAT).fidelity(Fidelity.HIGH).build(), FLAT));
        ts.add(s.submit(fall(81).priority(Priority.PLANNING).fidelity(Fidelity.HIGH).build(), FLAT));
        ts.add(s.submit(fall(82).priority(Priority.PLANNING).fidelity(Fidelity.HIGH).build(), FLAT));
        ts.add(s.submit(fall(83).priority(Priority.PLANNING).fidelity(Fidelity.HIGH).build(), FLAT));
        while (s.runNext(0)) ;
        assertEquals(Fidelity.HIGH, ts.get(0).result().fidelity); // combat never degrades
        assertEquals(Fidelity.STANDARD, ts.get(1).result().fidelity); // 2 left of 4
        assertTrue(ts.get(1).result().has(Assumption.DEGRADED));
        assertEquals(Fidelity.HIGH, ts.get(3).result().fidelity); // queue empty by then
        assertEquals(1, s.telemetry().degraded());
        // a degraded answer is not cached for the next full-fidelity asker
        PredictionTicket again = s.submit(fall(81).priority(Priority.PLANNING).fidelity(Fidelity.HIGH).build(), FLAT);
        assertFalse(again.isDone());
    }

    @Test
    public void staleCopiesAreFlagged() throws InterruptedException {
        Terrain t = PpeWorlds.flat(PpeWorlds.STONE);
        PredictionScheduler s = scheduler(8);
        PredictionTicket k = s.submit(fall(80).build(), t);
        s.runNext(0);
        t.section(0, 3, 0).supersede();
        assertTrue(k.result().has(Assumption.STALE_SNAPSHOT));
        assertEquals(PredictionResult.Confidence.APPROXIMATE, k.result().confidence);
    }

    /** The same section copies in a snapshot taken {@code tick} ticks later (what the capture hands out while nothing changed). */
    private static Terrain later(Terrain t, long tick) {
        Terrain.Builder b = Terrain.builder(t.minY, t.maxY, tick, t.sectionCount());
        for (int sx = -2; sx <= 1; sx++) for (int sy = 2; sy <= 6; sy++) for (int sz = -2; sz <= 1; sz++) b.put(sx, sy, sz, t.section(sx, sy, sz));
        return b.build();
    }

    @Test
    public void unchangedCopiesKeepTheirCachedAnswers() throws InterruptedException {
        Terrain t = PpeWorlds.flat(PpeWorlds.STONE);
        PredictionScheduler s = scheduler(8);
        s.submit(fall(80).build(), t);
        s.runNext(0);
        Terrain old = later(t, 40);
        assertEquals(t.fingerprint, old.fingerprint);
        PredictionTicket aged = s.submit(fall(80).build(), old);
        assertTrue(aged.isDone()); // same copies: same answer, from the cache
        assertEquals(40, aged.result().snapshotAgeTicks);
        // found unchanged at tick 40: the same copies, re-dated, so the same key and a current age
        for (int sx = -2; sx <= 1; sx++) for (int sy = 2; sy <= 6; sy++) for (int sz = -2; sz <= 1; sz++) t.section(sx, sy, sz).recheck(40);
        Terrain rechecked = later(t, 40);
        assertEquals(t.fingerprint, rechecked.fingerprint);
        assertEquals(0, rechecked.maxAge());
        assertEquals(0, s.submit(fall(80).build(), rechecked).result().snapshotAgeTicks);
        assertFalse(aged.result().has(Assumption.STALE_SNAPSHOT));
    }

    @Test
    public void workerThreadDrainsTheQueue() throws InterruptedException {
        PredictionScheduler s = scheduler(64);
        s.start();
        List<PredictionTicket> ts = new ArrayList<>();
        for (int i = 0; i < 32; i++) ts.add(s.submit(fall(70 + i).build(), FLAT));
        long until = System.nanoTime() + 10_000_000_000L;
        while (ts.stream().anyMatch(t -> !t.isDone()) && System.nanoTime() < until) Thread.sleep(1);
        s.stop();
        for (PredictionTicket t : ts) assertTrue(t.result().ok());
        assertTrue(s.telemetry().computeMax() >= s.telemetry().computeP50());
    }
    @Test
    public void completedAndCacheHitTicketsNoticeLaterInvalidation() throws InterruptedException {
        Terrain terrain = PpeWorlds.flat(PpeWorlds.STONE);
        PredictionScheduler s = scheduler(8);
        PredictionTicket first = s.submit(fall(80).build(), terrain);
        s.runNext(0);
        assertFalse(first.result().has(Assumption.STALE_SNAPSHOT));
        PredictionTicket cached = s.submit(fall(80).build(), terrain);
        assertFalse(cached.result().has(Assumption.STALE_SNAPSHOT));
        terrain.section(0, 3, 0).supersede();
        assertTrue(first.result().has(Assumption.STALE_SNAPSHOT));
        assertTrue(cached.result().has(Assumption.STALE_SNAPSHOT));
        assertTrue(s.predict(fall(80).build(), terrain).has(Assumption.STALE_SNAPSHOT));
    }

    @Test
    public void stopCancelsAllTicketsAndRestartAcceptsFreshWork() throws InterruptedException {
        PredictionScheduler s = scheduler(64);
        List<PredictionTicket> tickets = new ArrayList<>();
        for (int i = 0; i < 32; i++) tickets.add(s.submit(fall(70 + i).build(), FLAT));
        s.stop();
        for (PredictionTicket ticket : tickets) assertEquals(Status.CANCELLED, ticket.result().status);
        assertEquals(0, s.queueDepth());
        s.start();
        try {
            PredictionTicket fresh = s.submit(fall(80).build(), FLAT);
            long until = System.nanoTime() + 5_000_000_000L;
            while (!fresh.isDone() && System.nanoTime() < until) Thread.sleep(1);
            assertTrue(fresh.isDone());
            assertEquals(Status.OK, fresh.result().status);
        } finally {
            s.stop();
        }
    }

    @Test
    public void hashCollisionsDoNotShareSimulation() throws InterruptedException {
        var a = fall(80).input(new PredictionRequest.Input(1, 0, false, false, false, 1, 2)).build();
        var b = fall(80).input(new PredictionRequest.Input(1, 0, false, false, false,
                Math.nextUp(1f), Float.intBitsToFloat(Float.floatToIntBits(2f) - 17))).build();
        assertEquals(a.key(), b.key()); // deliberate collision in the input hash
        assertFalse(a.sameSimulation(b));
        PredictionScheduler s = scheduler(8);
        s.submit(a, FLAT);
        s.submit(b, FLAT);
        assertEquals(2, s.queueDepth());
        s.runNext(0);
        s.runNext(0);
        assertEquals(0, s.telemetry().dedupHits());
    }

    @Test
    public void consumersCannotCorruptSharedPaths() throws InterruptedException {
        PredictionScheduler s = scheduler(8);
        PredictionTicket a = s.submit(fall(80).build(), FLAT);
        PredictionTicket b = s.submit(fall(80).build(), FLAT);
        s.runNext(0);
        float original = a.result().path[0];
        a.result().path[0] = 12345;
        assertEquals(original, b.result().path[0], 0);
        assertEquals(original, s.predict(fall(80).build(), FLAT).path[0], 0);
    }

    @Test
    public void excessiveOrNonFiniteRequestsAreRejectedBeforeQueueing() {
        assertThrows(IllegalArgumentException.class, () -> BodyState.builder(BodyState.Type.PLAYER).vel(Double.POSITIVE_INFINITY, 0, 0).build());
        assertThrows(IllegalArgumentException.class, () -> BodyState.builder(BodyState.Type.PLAYER).size(1000, 1000, 1).build());
        assertThrows(IllegalArgumentException.class, () -> PredictionRequest.explosion(Double.NaN, 0, 0, 4, true).build());
        assertThrows(IllegalArgumentException.class, () -> fall(80).targets(java.util.Collections.nCopies(257,
                BodyState.builder(BodyState.Type.PLAYER).build())).build());
    }

    @Test
    public void sharedJobDoesNotExtendAnIndividualConsumersDeadline() throws InterruptedException {
        PredictionScheduler s = scheduler(8);
        PredictionTicket early = s.submit(fall(80).deadlineIn(50_000_000).build(), FLAT);
        PredictionTicket later = s.submit(fall(80).build(), FLAT);
        Thread.sleep(60);
        assertTrue(early.isDone());
        assertEquals(Status.EXPIRED, early.result().status);
        assertFalse(later.isDone());
        s.runNext(0);
        assertEquals(Status.OK, later.result().status);
        var expired = fall(80).deadlineIn(-1).build();
        assertEquals(Status.EXPIRED, s.submit(expired, FLAT).result().status);
        assertEquals(Status.EXPIRED, s.predict(expired, FLAT).status);
    }

}
