package baritone.swarm.protocol;

import baritone.swarm.protocol.Phase1Support.Pair;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: on a bot with leases off, mine is its owner's only: a plain mine from another member is unauthorized
 * (mine from a non-owner runs only under a coordinator's lease, and no lease can be had there). Other swarm commands
 * are unchanged.
 */
public class MineWithoutLeasesTest {

    @Test
    public void aNonOwnersPlainMineIsUnauthorized() {
        Pair pair = new Pair("Zed"); // neither Alice nor Bob is the owner
        String ack = pair.alice.submit("Bob", "mine diamond_ore");
        assertEquals(0, pair.bobExec.count("mine"));
        assertTrue(ack + " " + pair.log, ack.contains("unauthorized") || pair.log.stream().anyMatch(l -> l.contains("unauthorized")));
    }

    @Test
    public void theOwnersPlainMineStillRuns() {
        Pair pair = new Pair("Alice");
        pair.alice.submit("Bob", "mine diamond_ore");
        assertEquals(1, pair.bobExec.count("mine"));
    }

    @Test
    public void otherSwarmCommandsFromANonOwnerAreUnchanged() {
        Pair pair = new Pair("Zed");
        pair.alice.submit("Bob", "goto 10 64 10");
        assertEquals(1, pair.bobExec.count("goto"));
    }
}
