package baritone.swarm.protocol;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import baritone.swarm.protocol.Phase1Support.Pair;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Ostinato: a command whose process went idle without a recorded outcome is running with observation unknown,
// never succeeded (verified) (release gate, 17 Nov: a mine with nothing to mine reported succeeded).
public class NoOpNotSucceededTest {
    @Test
    public void unverifiedIdleIsRunningWithObservationUnknown() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "mine diamond_ore"));
        pair.bobExec.idle = true;
        pair.bob.pollExecutions();
        assertEquals(ExecutionState.RUNNING, pair.bob.lookup(id).execution);
        assertEquals(ObservationState.UNKNOWN, pair.bob.lookup(id).observation);
        assertTrue(pair.log.stream().anyMatch(l -> l.contains(id + " went idle without a verified result")));
        assertFalse(pair.alice.outcomeLine(id.toString()).contains("succeeded"));
        int lines = pair.log.size();
        pair.bob.pollExecutions(); // logged once
        assertEquals(lines, pair.log.size());
    }

    @Test
    public void aLaterRecordedFailureStillEndsIt() {
        Pair pair = new Pair();
        UUID id = Phase1Support.idOf(pair.alice.submit("Bob", "mine diamond_ore"));
        pair.bobExec.idle = true;
        pair.bob.pollExecutions();
        pair.bobExec.idle = false;
        pair.bobExec.poll = ExecutionState.FAILED;
        pair.bob.pollExecutions();
        assertEquals(ExecutionState.FAILED, pair.bob.lookup(id).execution);
        assertTrue(pair.alice.outcomeLine(id.toString()).contains("failed"));
    }
}
