package baritone.swarm.protocol;

import baritone.swarm.protocol.Phase1Support.Pair;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: commands that are complete without arguments (path, come, farm, explore, tunnel, goal, home) can be sent bare
 * over the swarm; the catalog used to refuse them all with "missing arguments". Commands that cannot run bare still are.
 */
public class BareCommandsTest {

    private static final String[] BARE_OK = {"path", "come", "farm", "explore", "tunnel", "goal", "home"};
    private static final String[] NEED_ARGS = {"goto", "follow", "mine", "build"};

    @Test
    public void bareCommandsValidate() {
        for (String name : BARE_OK) {
            assertNull(name, CommandCatalog.infer(name, null).validate(""));
        }
    }

    @Test
    public void commandsThatNeedArgumentsStillRefuseBare() {
        for (String name : NEED_ARGS) {
            assertEquals(name, "missing arguments", CommandCatalog.infer(name, null).validate(""));
            assertNull(name, CommandCatalog.infer(name, null).validate("x"));
        }
    }

    @Test
    public void aBarePathAndFarmReachTheBot() {
        for (String name : new String[]{"path", "farm"}) {
            Pair pair = new Pair(); // Alice is the owner: path and farm are owner-only over the swarm
            pair.alice.submit("Bob", name);
            assertEquals(name + " " + pair.log, 1, pair.bobExec.count(name));
            assertFalse(pair.log.toString(), pair.log.stream().anyMatch(l -> l.contains("missing arguments")));
        }
    }
}
