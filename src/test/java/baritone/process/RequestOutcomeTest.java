package baritone.process;

import baritone.api.process.ProcessOutcome;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: the request-outcome rule the idle commands now share with mine, and follow's lost-target clock. */
public class RequestOutcomeTest {

    @Test
    public void endNowFailsAFollowThatNeverHadATargetAndALaterStopKeepsIt() {
        RequestOutcome o = new RequestOutcome();
        o.start();
        o.endNow(ProcessOutcome.Reason.INVALIDATED_GOAL);
        assertEquals(ProcessOutcome.State.FAILED, o.outcome().state());
        o.finish(); // the stop that comes later
        assertEquals(ProcessOutcome.State.FAILED, o.outcome().state());
        assertEquals(ProcessOutcome.Reason.INVALIDATED_GOAL, o.outcome().reason());
    }

    @Test
    public void workAfterTheRequestEndedDoesNotCount() {
        RequestOutcome o = new RequestOutcome();
        o.start();
        o.endNow(ProcessOutcome.Reason.MISSING_RESOURCE);
        o.didOne();
        assertEquals(0, o.done());
        assertEquals(ProcessOutcome.State.FAILED, o.outcome().state());
    }

    @Test
    public void aGoalReachedIsWorkSoTheRequestSucceeds() {
        RequestOutcome o = new RequestOutcome();
        o.start();
        o.running();
        o.didOne();
        o.endFor(ProcessOutcome.Reason.NONE);
        o.finish();
        assertEquals(ProcessOutcome.State.SUCCEEDED, o.outcome().state());
    }

    @Test
    public void noRequestNoOutcome() {
        RequestOutcome o = new RequestOutcome();
        o.didOne();
        o.finish();
        assertEquals(ProcessOutcome.NONE, o.outcome());
        assertFalse(o.open());
    }

    @Test
    public void followIsLostOnlyAfterTheGracePeriod() {
        long start = 1000;
        assertFalse(FollowProcess.lost(Long.MIN_VALUE, start, start + FollowProcess.LOST_TICKS - 1));
        assertTrue("nothing to follow from the start", FollowProcess.lost(Long.MIN_VALUE, start, start + FollowProcess.LOST_TICKS));
        assertFalse("a target seen recently", FollowProcess.lost(start + 150, start, start + 200));
        assertTrue(FollowProcess.lost(start + 150, start, start + 150 + FollowProcess.LOST_TICKS));
    }
}
