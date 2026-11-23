package baritone.swarm.lease;

import baritone.swarm.protocol.CommandProtocol;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: `mine` (SWARM) is lease-scoped, as the owner-only leased tunnel is: a non-owner issuer may run it only as a
 * leased command carrying a valid, current lease granted by the configured coordinator (any reservation type: vein,
 * region), and so only inside that lease's fence. The owner is not limited. Before, any swarm member could send a bot
 * with leases off a plain mine, anywhere.
 */
public class MineLeaseAuthorityTest {

    private static final String VEIN = "v:o:0,10,0:1,11,1";
    private static final String REGION = "r:o:0,10,0:9,12,9";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Bob's owner is Zed, so Alice (the coordinator) is not the owner. */
    private LeaseSupport nonOwnerCoordinator() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath(), "Zed");
        s.activate();
        return s;
    }

    private static boolean rejectedUnauthorized(LeaseSupport s) {
        return s.logged("unauthorized") && s.bobExec.count("mine") == 0;
    }

    @Test
    public void aLeasedMineWithAValidVeinLeaseFromTheCoordinatorRuns() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", VEIN, "mine iron_ore");
        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
        assertFalse(s.logged("unauthorized"));
    }

    @Test
    public void aRegionLeasedMineRunsToo() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", REGION, "mine coal_ore"); // command-center area orders
        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
    }

    @Test
    public void aPlainMineFromTheCoordinatorIsUnauthorizedNotNolease() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.aliceP.submit("Bob", "mine iron_ore"); // not a leased command
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
        assertFalse(s.logged("nolease"));
    }

    @Test
    public void anExpiredLeaseIsUnauthorized() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", VEIN, "mine iron_ore");
        List<LeaseSupport.Msg> batch;
        while (!(batch = s.take(LeaseMessage.TYPE, null)).isEmpty()) {
            for (LeaseSupport.Msg m : batch) {
                s.deliver(m);
            }
        }
        s.advance(40_000L); // past the 30 s test lease, inside the command's own deadline
        s.guard.tick();
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
    }

    @Test
    public void aLeaseFromAMemberThatIsNoLongerTheCoordinatorIsUnauthorized() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", VEIN, "mine iron_ore");
        List<LeaseSupport.Msg> batch;
        while (!(batch = s.take(LeaseMessage.TYPE, null)).isEmpty()) {
            for (LeaseSupport.Msg m : batch) {
                s.deliver(m);
            }
        }
        s.guardCoordinator = "Carol"; // Bob's configured coordinator changes before Alice's leased mine arrives
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
    }

    @Test
    public void aNonCoordinatorsPlainMineIsUnauthorizedAtOnce() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.guardCoordinator = "Carol";
        s.aliceP.submit("Bob", "mine iron_ore");
        s.pump();
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
    }

    @Test
    public void theOwnersMineIsNotLeaseScoped() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath()); // Alice is Bob's owner
        s.activate();
        s.coord.lease("Bob", VEIN, "mine iron_ore");
        s.pump();
        assertEquals(1, s.bobExec.count("mine"));
        assertFalse(s.logged("unauthorized"));
    }
}
