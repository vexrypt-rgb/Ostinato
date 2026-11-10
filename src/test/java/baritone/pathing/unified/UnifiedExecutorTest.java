/*
 * Ostinato 2.0 Phase 06: unified executor ownership tests. A fake key map and the real arbiter; no client.
 */
package baritone.pathing.unified;

import baritone.Baritone;
import baritone.api.utils.input.Input;
import baritone.pathing.unified.UnifiedExecutor.Cause;
import baritone.pathing.unified.UnifiedExecutor.Counter;
import baritone.pathing.unified.UnifiedExecutor.Outcome;
import baritone.utils.InputArbiter;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UnifiedExecutorTest {

    /** The raw key map. */
    static final class FakeKeys implements UnifiedExecutor.Keys {
        final EnumSet<Input> down = EnumSet.noneOf(Input.class);
        boolean analog;

        @Override
        public boolean forcedRaw(Input input) {
            return down.contains(input);
        }

        @Override
        public boolean analogForced() {
            return analog;
        }

        @Override
        public void releaseRaw(Input input) {
            down.remove(input);
        }
    }

    private final Object path = new Object();
    private InputArbiter arb;
    private FakeKeys keys;
    private UnifiedExecutor ux;

    @Before
    public void setUp() {
        arb = new InputArbiter();
        keys = new FakeKeys();
        ux = new UnifiedExecutor(arb, keys);
    }

    /** One whole tick where physics drives at pos and holds forward. */
    private void drive(int pos) {
        ux.beginTick();
        assertTrue(ux.beginWrite(path, pos));
        keys.down.add(Input.MOVE_FORWARD);
        ux.endWrite(true, false, false);
        ux.endTick(true);
    }

    @Test
    public void settingIsOffByDefault() {
        baritone.pathing.calc.PathBench.boot(); // Baritone.settings() needs the registries
        assertFalse(Baritone.settings().unifiedExecutor.defaultValue);
    }

    @Test
    public void nothingHappensWithoutALease() {
        ux.beginTick();
        ux.emergency();
        ux.cancelRefused();
        ux.cancelConfirmed(Cause.SOFT_CANCEL);
        ux.segmentEnded(true);
        ux.fail(Cause.M01);
        ux.yield(3);
        ux.handback(Cause.DROP, 3);
        ux.stopForCancel();
        ux.worldChanged();
        ux.processesCancelled();
        ux.endTick(true);
        assertNull(ux.state());
        assertFalse(ux.refusesPress(Input.MOVE_BACK));
        assertFalse(ux.refusesAnalog());
        for (long c : ux.snapshot()) {
            assertEquals(0, c);
        }
    }

    @Test
    public void completionIsVerifiedOnlyWhenTheKeysAreUp() {
        drive(0);
        drive(2);
        keys.down.clear(); // pathing clears the keys before it reports
        ux.segmentEnded(true);
        assertEquals(Outcome.COMPLETED_VERIFIED, ux.state());
        assertEquals(1, ux.count(Counter.COMPLETIONS));
        assertEquals(1, ux.count(Counter.ACQUIRES));

        drive(0);
        drive(2);
        ux.segmentEnded(true); // forward still down: not verified
        assertEquals(Outcome.UNKNOWN, ux.state());
        assertEquals(1, ux.count(Counter.UNKNOWNS));
        assertEquals(2, ux.count(Counter.ACQUIRES));
    }

    @Test
    public void unfinishedSegmentFails() {
        drive(0);
        keys.down.clear();
        ux.segmentEnded(false);
        assertEquals(Outcome.FAILED, ux.state());
        assertEquals(Cause.SEGMENT_END, ux.lastCause());
    }

    @Test
    public void partlyPreemptedLeaseIsStaleAndRejected() {
        drive(0);
        arb.claim("fall", 10, false, InputArbiter.Resource.MOVE); // outranks physics on MOVE only
        assertFalse("physics keeps JUMP and SNEAK but is no longer whole", ux.held());
        ux.beginTick();
        assertFalse(ux.beginWrite(path, 1));
        assertEquals(1, ux.count(Counter.STALE_REJECTS));
        assertEquals(Outcome.UNKNOWN, ux.state());
        assertEquals(Cause.STALE, ux.lastCause());
        assertFalse(ux.leased());
        assertTrue("fall keeps its key", arb.claim("fall", 10, false, InputArbiter.Resource.MOVE).holds(InputArbiter.Resource.MOVE));
    }

    @Test
    public void reclaimBumpsTheEpochAndMakesTheLeaseStale() {
        drive(0);
        assertTrue(ux.held());
        arb.claim(UnifiedExecutor.OWNER, UnifiedExecutor.PRIORITY, false,
                InputArbiter.Resource.MOVE, InputArbiter.Resource.JUMP, InputArbiter.Resource.SNEAK);
        assertFalse("same owner, new epoch", ux.held());
        ux.beginTick();
        assertFalse(ux.beginWrite(path, 1));
        assertEquals(1, ux.count(Counter.STALE_REJECTS));
    }

    @Test
    public void refusedClaimBacksOffForTheRetryWindow() {
        arb.claim("fall", 10, false, InputArbiter.Resource.MOVE);
        ux.beginTick();
        assertFalse(ux.beginWrite(path, 0));
        assertEquals(Outcome.FAILED, ux.state());
        assertEquals(Cause.REFUSED, ux.lastCause());
        assertEquals(1, ux.count(Counter.ACQUIRE_REFUSED));
        assertTrue("the refused claim let go of its partial keys",
                arb.claim("probe", 1, false, InputArbiter.Resource.JUMP).holds(InputArbiter.Resource.JUMP));
        arb.confirmRelease("probe");
        for (int i = 1; i < UnifiedExecutor.RETRY_TICKS; i++) {
            ux.beginTick();
            assertFalse(ux.beginWrite(path, 0));
            ux.endTick(true);
        }
        assertEquals("no claim inside the window", 1, ux.count(Counter.ACQUIRE_REFUSED));
        arb.confirmRelease("fall");
        ux.beginTick();
        assertTrue(ux.beginWrite(path, 0));
        assertEquals(1, ux.count(Counter.ACQUIRES));
    }

    @Test
    public void emergencyPreemptsThenLetsGo() {
        drive(0);
        keys.down.clear(); // the emergency branch clears every key first
        ux.emergency();
        assertEquals(Outcome.CANCELLED_CONFIRMED, ux.state());
        assertEquals(Cause.EMERGENCY, ux.lastCause());
        assertEquals(1, ux.count(Counter.PREEMPTIONS));
        assertFalse(ux.leased());
        assertFalse(ux.refusesPress(Input.MOVE_BACK));
        assertTrue("nobody is left holding MOVE",
                arb.claim("other", 1, false, InputArbiter.Resource.MOVE).holds(InputArbiter.Resource.MOVE));
    }

    @Test
    public void emergencyWithOurKeyStillDownIsUnknown() {
        drive(0);
        ux.emergency();
        assertEquals(Outcome.UNKNOWN, ux.state());
        assertEquals(1, ux.count(Counter.UNKNOWNS));
    }

    @Test
    public void failReleasesTheDriversKeysAndTheLease() {
        drive(0);
        keys.down.add(Input.SPRINT); // not ours: left alone
        ux.fail(Cause.M01);
        assertFalse(keys.down.contains(Input.MOVE_FORWARD));
        assertTrue(keys.down.contains(Input.SPRINT));
        assertEquals(Outcome.FAILED, ux.state());
        assertEquals(Cause.M01, ux.lastCause());
        assertEquals(1, ux.count(Counter.FAILURES));
        assertFalse(ux.leased());
        InputArbiter.Lease other = arb.claim("other", 1, false, InputArbiter.Resource.MOVE);
        assertTrue("a lower priority claim gets through after the failure", other.holds(InputArbiter.Resource.MOVE));
    }

    @Test
    public void cancelStopsAfterTwoRefusalsOnTheGround() {
        drive(0);
        ux.beginTick();
        ux.cancelRefused();
        ux.cancelRefused(); // once a tick
        assertEquals(1, ux.count(Counter.CANCEL_REFUSALS));
        assertFalse(ux.cancelDue(true));
        assertTrue(ux.beginWrite(path, 1));
        ux.endWrite(true, false, false);
        ux.endTick(true);

        ux.beginTick();
        ux.cancelRefused();
        assertFalse("not mid-air", ux.cancelDue(false));
        assertTrue(ux.cancelDue(true));
        ux.stopForCancel();
        assertFalse("the stop lifts its own keys", keys.down.contains(Input.MOVE_FORWARD));
        assertTrue(ux.stoppedForCancel());
        assertEquals(Outcome.CANCELLED_CONFIRMED, ux.state());
        assertEquals(Cause.CANCEL_STOP, ux.lastCause());
        assertEquals(1, ux.count(Counter.CANCEL_STOPS));
        assertEquals(1, ux.count(Counter.CANCEL_LATENCY_MAX));
        ux.endTick(true);
        ux.beginTick();
        assertFalse(ux.stoppedForCancel());
    }

    @Test
    public void brokenRefusalStreakStartsOver() {
        drive(0);
        ux.beginTick();
        ux.cancelRefused();
        ux.beginWrite(path, 1);
        ux.endTick(true);
        ux.beginTick(); // no refusal this tick
        ux.beginWrite(path, 1);
        ux.endTick(true);
        ux.beginTick();
        assertFalse(ux.cancelDue(true));
        ux.cancelRefused();
        assertFalse("one refusal in the new streak", ux.cancelDue(true));
    }

    @Test
    public void confirmedCancelRecordsLatency() {
        drive(0);
        ux.beginTick();
        ux.cancelRefused();
        ux.beginWrite(path, 1);
        ux.endTick(true);
        ux.beginTick();
        ux.beginTick();
        ux.cancelConfirmed(Cause.SEGMENT_CANCEL);
        assertEquals(Outcome.CANCELLED_CONFIRMED, ux.state());
        assertEquals(1, ux.count(Counter.CANCELS));
        assertEquals(2, ux.count(Counter.CANCEL_LATENCY_SUM));
    }

    @Test
    public void cancelLeavesOtherOwnersKeysAlone() {
        drive(0);
        keys.down.add(Input.SPRINT);
        ux.cancelConfirmed(Cause.PAUSE);
        assertFalse(keys.down.contains(Input.MOVE_FORWARD));
        assertTrue(keys.down.contains(Input.SPRINT));
        assertEquals(Outcome.CANCELLED_CONFIRMED, ux.state());
        assertEquals("no request was pending", 0, ux.count(Counter.CANCEL_LATENCY_SUM));
    }

    @Test
    public void worldChangeCancels() {
        drive(0);
        ux.worldChanged();
        assertEquals(Cause.WORLD_CHANGE, ux.lastCause());
        assertEquals(Outcome.CANCELLED_CONFIRMED, ux.state());
        assertFalse(ux.leased());
    }

    @Test
    public void flagOffHandsBackWithoutBlamingTheLegacyKeys() {
        drive(0);
        drive(2);
        ux.beginTick();
        keys.down.add(Input.MOVE_FORWARD); // the legacy driver presses forward
        ux.endTick(false);
        assertEquals(Cause.FLAG_OFF, ux.lastCause());
        assertEquals(Outcome.COMPLETED_VERIFIED, ux.state());
        assertEquals(1, ux.count(Counter.HANDOVERS));
        assertFalse(ux.leased());
    }

    @Test
    public void idleHandbackWithOurKeyStillDownIsUnknown() {
        drive(0);
        drive(2);
        ux.beginTick();
        ux.endTick(true); // nobody drove; our forward is still down
        assertEquals(Cause.IDLE, ux.lastCause());
        assertEquals(Outcome.UNKNOWN, ux.state());
    }

    @Test
    public void idleHandbackWithoutProgressIsUnknown() {
        drive(3);
        keys.down.clear();
        ux.beginTick();
        ux.endTick(true);
        assertEquals(Outcome.UNKNOWN, ux.state());
    }

    @Test
    public void yieldIsVerifiedDespiteTheNextDriversKeys() {
        drive(0);
        ux.beginTick();
        ux.beginPass();
        keys.down.add(Input.MOVE_FORWARD); // kinematic took the tick and holds forward
        ux.yield(2);
        assertEquals(Cause.YIELD, ux.lastCause());
        assertEquals(Outcome.COMPLETED_VERIFIED, ux.state());
        assertTrue(keys.down.contains(Input.MOVE_FORWARD));
        assertEquals(0, ux.count(Counter.PREEMPTIONS));
        ux.endTick(true);
        assertEquals("no second hand-back", 1, ux.count(Counter.HANDOVERS));
    }

    @Test
    public void handbackLiftsTheDriversKeys() {
        drive(0);
        ux.handback(Cause.DROP, 1);
        assertFalse(keys.down.contains(Input.MOVE_FORWARD));
        assertEquals(Outcome.COMPLETED_VERIFIED, ux.state());
        assertEquals(Cause.DROP, ux.lastCause());
    }

    @Test
    public void gateRefusesOtherWritersOnlyAfterTheDriverInItsTick() {
        ux.beginTick();
        assertFalse(ux.refusesPress(Input.MOVE_BACK));
        assertTrue(ux.beginWrite(path, 0));
        assertFalse("the driver's own writes", ux.refusesPress(Input.MOVE_FORWARD));
        ux.endWrite(true, false, false);
        assertTrue(ux.refusesPress(Input.MOVE_BACK));
        assertTrue(ux.refusesAnalog());
        assertFalse("clicks are not leased", ux.refusesPress(Input.CLICK_LEFT));
        assertFalse(ux.refusesPress(Input.CLICK_RIGHT));
        assertFalse(ux.refusesPress(null));
        assertEquals(2, ux.count(Counter.CONFLICTS));
        ux.beginPass();
        assertFalse("a new pass opens the gate", ux.refusesPress(Input.MOVE_BACK));
        ux.endTick(true);
        ux.beginTick();
        assertFalse(ux.refusesPress(Input.MOVE_BACK));
    }

    @Test
    public void gateIsOpenOnceTheLeaseIsLost() {
        ux.beginTick();
        assertTrue(ux.beginWrite(path, 0));
        ux.endWrite(true, false, false);
        arb.claim("fall", 10, false, InputArbiter.Resource.MOVE);
        assertFalse(ux.refusesPress(Input.MOVE_BACK));
    }

    @Test
    public void endTickClassifiesTheOwnedKeys() {
        drive(0);
        assertEquals(1, ux.count(Counter.INTACT_TICKS));

        ux.beginTick();
        ux.beginWrite(path, 1);
        keys.down.add(Input.MOVE_FORWARD);
        ux.endWrite(true, false, false);
        keys.down.remove(Input.MOVE_FORWARD); // someone let go of our key
        ux.endTick(true);
        assertEquals(1, ux.count(Counter.RELEASED_EARLY));

        ux.beginTick();
        ux.beginWrite(path, 2);
        keys.down.add(Input.MOVE_FORWARD);
        ux.endWrite(true, false, false);
        keys.down.add(Input.MOVE_BACK); // someone pressed a leased key
        ux.endTick(true);
        assertEquals(1, ux.count(Counter.OVERWRITTEN));

        keys.down.remove(Input.MOVE_BACK);
        ux.beginTick();
        ux.beginWrite(path, 3);
        ux.endWrite(true, false, false);
        keys.analog = true; // an analog vector overrides the keys
        ux.endTick(true);
        assertEquals(2, ux.count(Counter.OVERWRITTEN));
        assertEquals(4, ux.count(Counter.OWNED_TICKS));
    }

    @Test
    public void keyPressedBetweenOwnedTicksCountsAsErased() {
        drive(0);
        keys.down.add(Input.SNEAK);
        ux.beginTick();
        assertTrue(ux.beginWrite(path, 1));
        assertEquals(1, ux.count(Counter.ERASED));
    }

    @Test
    public void twoPassesInOneTickCountOneOwnedTick() {
        ux.beginTick();
        assertTrue(ux.beginWrite(path, 0));
        ux.endWrite(true, false, false);
        ux.beginPass();
        assertTrue(ux.beginWrite(path, 1));
        ux.endWrite(true, false, false);
        assertEquals(1, ux.count(Counter.OWNED_TICKS));
    }

    @Test
    public void newPathHandsBackAndTakesAFreshLease() {
        drive(0);
        drive(2);
        keys.down.clear();
        ux.beginTick();
        assertTrue(ux.beginWrite(new Object(), 0));
        assertEquals(2, ux.count(Counter.ACQUIRES));
        assertEquals(1, ux.count(Counter.COMPLETIONS));
        assertEquals(Outcome.EXECUTING, ux.state());
        assertTrue(ux.held());
    }

    @Test
    public void releaseAfterFailureLetsTheDriverClaimAgain() {
        drive(0);
        ux.fail(Cause.EMPTY_PLAN);
        ux.beginTick();
        assertTrue(ux.beginWrite(path, 0));
        assertTrue(ux.held());
        assertEquals(2, ux.count(Counter.ACQUIRES));
    }

    // ---- every process cancelled while the segment cancel is refused (PathingBehavior.cancelEverything) ----

    /** One tick in which the driver writes and then, later in the tick, every process is cancelled while unsafe. */
    private void writeThenCancelProcesses(int pos, boolean notify) {
        ux.beginTick();
        assertTrue(ux.beginWrite(path, pos));
        keys.down.add(Input.MOVE_FORWARD);
        ux.endWrite(true, false, false);
        ux.cancelRefused();
        if (notify) {
            ux.processesCancelled();
        }
        keys.down.clear(); // a process's onLostControl clears every key
        ux.endTick(true);
    }

    @Test
    public void keysClearedBehindAHeldLeaseAreReleasedEarly() {
        // the Phase 06 defect: nothing tells the executor, so its own keys go up behind its lease
        drive(0);
        writeThenCancelProcesses(1, false);
        assertEquals(1, ux.count(Counter.RELEASED_EARLY));
        assertTrue("the lease is still held", ux.leased());
        ux.beginTick();
        ux.endTick(true);
        assertEquals("handed back a tick later, unacknowledged", 1, ux.count(Counter.HANDOVERS));
    }

    @Test
    public void processCancelMidMoveGivesUpTheLeaseBeforeTheKeysGoUp() {
        drive(0);
        ux.beginTick();
        assertTrue(ux.beginWrite(path, 1));
        keys.down.add(Input.MOVE_FORWARD);
        ux.endWrite(true, false, false);
        ux.cancelRefused();
        ux.processesCancelled();
        assertFalse("the driver lifted its own key", keys.down.contains(Input.MOVE_FORWARD));
        assertFalse(ux.leased());
        keys.down.clear();
        ux.endTick(true);
        assertEquals(Outcome.CANCELLED_CONFIRMED, ux.state());
        assertEquals(Cause.PROCESS_CANCEL, ux.lastCause());
        assertEquals(1, ux.count(Counter.PROCESS_CANCELS));
        assertEquals(1, ux.count(Counter.CANCEL_REFUSALS));
        assertEquals(0, ux.count(Counter.RELEASED_EARLY));
        assertEquals(0, ux.count(Counter.HANDOVERS));
        assertEquals(0, ux.count(Counter.UNKNOWNS));
        assertEquals("ends the lease, not the segment: no cancel latency", 0, ux.count(Counter.CANCEL_LATENCY_SUM));
        assertFalse(ux.refusesPress(Input.MOVE_BACK));
        assertFalse(ux.refusesAnalog());
        assertTrue("nobody is left holding MOVE",
                arb.claim("other", 1, false, InputArbiter.Resource.MOVE).holds(InputArbiter.Resource.MOVE));
    }

    @Test
    public void processCancelBetweenLapsLeavesNothingForTheNextTicks() {
        drive(0);
        writeThenCancelProcesses(1, true);
        long[] after = ux.snapshot();
        ux.beginTick();
        ux.endTick(false); // the harness switched arms: flag off
        ux.beginTick();
        ux.endTick(true);
        long[] later = ux.snapshot();
        for (int i = 0; i < after.length; i++) {
            assertEquals(Counter.values()[i].name(), after[i], later[i]);
        }
        assertEquals(0, ux.count(Counter.HANDOVERS));
        assertEquals(0, ux.count(Counter.RELEASED_EARLY));
        assertEquals(0, ux.count(Counter.COMPLETIONS));
        assertEquals(Cause.PROCESS_CANCEL, ux.lastCause());
    }

    @Test
    public void segmentRunningOnAfterProcessCancelTakesAFreshLeaseAndStopsLater() {
        drive(0);
        writeThenCancelProcesses(1, true);
        // next tick: preTick asks the segment to cancel before the driver writes; there is no lease, so it does not count
        ux.beginTick();
        ux.cancelRefused();
        assertEquals(1, ux.count(Counter.CANCEL_REFUSALS));
        assertFalse(ux.cancelDue(true));
        assertTrue(ux.beginWrite(path, 2));
        assertTrue(ux.held());
        assertEquals(2, ux.count(Counter.ACQUIRES));
        keys.down.add(Input.MOVE_FORWARD);
        ux.endWrite(true, false, false);
        ux.endTick(true);
        // the count starts over from the new lease
        ux.beginTick();
        ux.cancelRefused();
        assertFalse("one refusal on the new lease is not enough", ux.cancelDue(true));
        assertTrue(ux.beginWrite(path, 3));
        ux.endWrite(true, false, false);
        ux.endTick(true);
        ux.beginTick();
        ux.cancelRefused();
        assertTrue(ux.cancelDue(true));
        keys.down.clear();
        ux.stopForCancel();
        assertTrue(ux.stoppedForCancel());
        ux.endTick(true);
        assertEquals(1, ux.count(Counter.CANCEL_STOPS));
        assertEquals(1, ux.count(Counter.PROCESS_CANCELS));
        assertEquals("latency counts from the first refusal on the new lease", 1, ux.count(Counter.CANCEL_LATENCY_MAX));
        assertEquals(0, ux.count(Counter.RELEASED_EARLY));
        assertEquals(0, ux.count(Counter.HANDOVERS));
        assertEquals(0, ux.count(Counter.UNKNOWNS));
        assertFalse(ux.leased());
    }

    @Test
    public void processCancelOnAPartlyPreemptedLeaseLeavesThePreemptorItsResource() {
        drive(0);
        arb.claim("fall", 10, false, InputArbiter.Resource.MOVE);
        ux.processesCancelled();
        // same as the other cancel paths: the lease is still alive in the arbiter, so it ends as a release
        assertEquals(Cause.PROCESS_CANCEL, ux.lastCause());
        assertFalse(ux.leased());
        assertTrue("fall keeps MOVE", arb.claim("fall", 10, false, InputArbiter.Resource.MOVE).holds(InputArbiter.Resource.MOVE));
        assertTrue("the driver let go of JUMP",
                arb.claim("other", 1, false, InputArbiter.Resource.JUMP).holds(InputArbiter.Resource.JUMP));
    }
}
