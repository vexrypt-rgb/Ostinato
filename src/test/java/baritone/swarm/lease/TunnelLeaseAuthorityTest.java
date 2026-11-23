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

import baritone.api.command.CommandPermission;
import baritone.swarm.protocol.CommandProtocol;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c part 2 slice 3 (owner decision, 21 Nov). `tunnel` is owner-only (ICommand's default). A non-owner
 * issuer may run it only as a leased command carrying a valid, current REGION lease from the configured coordinator,
 * and so only inside that region's fence. p2c2 s3run1: a swarm-issued leased tunnel was rejected `unauthorized`.
 */
public class TunnelLeaseAuthorityTest {

    private static final String REGION = "r:o:0,10,0:9,12,9";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Bob's owner is Zed, so Alice (the coordinator) is not the owner; tunnel is OWNER as it is live. */
    private LeaseSupport nonOwnerCoordinator() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath(), "Zed");
        s.bobExec.permissions.put("tunnel", CommandPermission.OWNER);
        s.activate();
        return s;
    }

    private static boolean rejectedUnauthorized(LeaseSupport s) {
        return s.logged("unauthorized") && s.bobExec.count("tunnel") == 0;
    }

    @Test
    public void aLeasedTunnelWithAValidRegionLeaseFromTheCoordinatorRuns() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", REGION, "tunnel 2 1 8");
        s.pump();
        assertEquals(1, s.bobExec.count("tunnel"));
        assertFalse(s.logged("unauthorized"));
    }

    @Test
    public void noLeaseIsUnauthorized() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.aliceP.submit("Bob", "tunnel 2 1 8"); // from the coordinator, but not as a leased command
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
        assertFalse("not the old nolease", s.logged("nolease"));
    }

    @Test
    public void anExpiredLeaseIsUnauthorized() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", REGION, "tunnel 2 1 8");
        // the lease messages arrive; the command is held back until the lease has run out
        List<LeaseSupport.Msg> batch;
        while (!(batch = s.take(LeaseMessage.TYPE, null)).isEmpty()) {
            for (LeaseSupport.Msg m : batch) {
                s.deliver(m);
            }
        }
        s.advance(40_000L); // past the 30 s test lease, inside the command's own 60 s deadline
        s.guard.tick();
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
    }

    @Test
    public void aCommandFromAMemberThatIsNotTheConfiguredCoordinatorIsUnauthorized() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", REGION, "tunnel 2 1 8");
        // Bob takes the lease from Alice while she is his coordinator ...
        List<LeaseSupport.Msg> batch;
        while (!(batch = s.take(LeaseMessage.TYPE, null)).isEmpty()) {
            for (LeaseSupport.Msg m : batch) {
                s.deliver(m);
            }
        }
        // ... then his configured coordinator becomes Carol before Alice's leased command arrives.
        s.guardCoordinator = "Carol";
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
    }

    @Test
    public void noLeaseIsTakenFromANonCoordinator() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.guardCoordinator = "Carol";
        s.coord.lease("Bob", REGION, "tunnel 2 1 8");
        s.pump();
        s.aliceP.submit("Bob", "tunnel 2 1 8");
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.logged("ignored offer from Alice"));
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
    }

    @Test
    public void aLeaseOnSomethingOtherThanARegionIsUnauthorized() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        s.coord.lease("Bob", "v:o:0,10,0:1,11,1", "tunnel 2 1 8"); // a vein lease, not a region
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertTrue(s.log.toString(), rejectedUnauthorized(s));
    }

    @Test
    public void otherOwnerOnlyCommandsStayOwnerOnly() throws Exception {
        LeaseSupport s = nonOwnerCoordinator();
        // Ostinato: build is owner-only and not lease-scoped (since main 1431a6807 mine is lease-scoped too, any lease type)
        s.bobExec.permissions.put("build", CommandPermission.OWNER); // owner-only live (ICommand default)
        s.coord.lease("Bob", "v:o:0,10,0:1,11,1", "build house");
        s.pump();
        s.run(CommandProtocol.HOLD_MS * 3, 500L);
        assertEquals("only tunnel and mine are lease-scoped", 0, s.bobExec.count("build"));
        assertTrue(s.logged("unauthorized"));
    }
}
