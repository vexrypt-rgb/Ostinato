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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c part 2, slice 3 (S5 for tunnel's region entry). A REGION lease is re-checked once, right before the
 * leased command's first break or place inside its box: loaded here and still authorized, else the action is denied
 * and the lease ends with the reason.
 */
public class LeaseRegionEntryTest {

    private static final String REGION = "r:o:0,10,0:9,12,9";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LeaseSupport startedRegionLease() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        s.coord.lease("Bob", REGION, "tunnel 2 1 8");
        s.pump();
        assertEquals(1, s.bobExec.count("tunnel"));
        return s;
    }

    private static UUID lease(LeaseSupport s) {
        for (LeaseSupport.Msg m : s.sent) {
            LeaseMessage lm = m.lease();
            if (lm != null && lm.op == LeaseMessage.Op.OFFER && REGION.equals(lm.resource)) {
                return lm.leaseId;
            }
        }
        throw new AssertionError("no offer");
    }

    private static boolean reported(LeaseSupport s, UUID id, String reason) {
        for (LeaseSupport.Msg m : s.sent) {
            LeaseMessage lm = m.lease();
            if (lm != null && lm.op == LeaseMessage.Op.REPORT && id.equals(lm.leaseId) && lm.extra.contains(reason)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void aLoadedAuthorizedRegionIsReVerifiedOnceAtEntry() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID id = lease(s);
        List<String> asked = new ArrayList<>();
        assertNull(s.guard.regionEntry(LeaseFence.Kind.BREAK, 'o', 5, 10, 5, k -> asked.add(k.toString())));
        assertEquals(List.of(REGION), asked);
        assertTrue(s.logged("swarm-lease: entering " + REGION + " re-verified"));
        assertNull(s.guard.regionEntry(LeaseFence.Kind.BREAK, 'o', 6, 10, 5, k -> asked.add(k.toString())));
        assertEquals("checked once", 1, asked.size());
        assertEquals(BotLeaseGuard.Phase.VALID, s.guard.phaseOf(id));
    }

    @Test
    public void notLoadedIsRefusedAndTheLeaseEndsWithTheReason() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID id = lease(s);
        String why = s.guard.regionEntry(LeaseFence.Kind.BREAK, 'o', 5, 10, 5, k -> false);
        assertEquals("region-entry: not loaded", why);
        assertEquals(BotLeaseGuard.Phase.EXPIRED, s.guard.phaseOf(id));
        assertTrue(s.logged("region-entry: not loaded for " + REGION));
        s.run(10_000L, 500L);
        StringBuilder rep = new StringBuilder();
        for (LeaseSupport.Msg m : s.sent) {
            LeaseMessage lm = m.lease();
            if (lm != null && lm.op == LeaseMessage.Op.REPORT) {
                rep.append(lm.extra).append(" | ");
            }
        }
        assertTrue("the coordinator hears why (once the stop is confirmed): " + rep, reported(s, id, "reason=region-entry:_not_loaded")); // the wire writes spaces as _
    }

    @Test
    public void anExpiredLeaseIsRefusedAtEntry() throws Exception {
        LeaseSupport s = startedRegionLease();
        s.advance(10 * 60_000L); // no tick: past the authorised time, the lease still marked active
        String why = s.guard.regionEntry(LeaseFence.Kind.PLACE, 'o', 5, 11, 5, k -> true);
        assertNotNull(why);
        assertTrue(why, why.startsWith("region-entry: not reserved"));
    }

    @Test
    public void aNewerGenerationOfTheRegionIsRefusedAtEntry() throws Exception {
        LeaseSupport s = startedRegionLease();
        UUID first = lease(s);
        s.coord.revoke(REGION);
        s.coord.lease("Bob", REGION, "tunnel 2 1 8"); // generation 2 of the same region
        s.pump();
        String why = s.guard.regionEntry(LeaseFence.Kind.BREAK, 'o', 5, 10, 5, k -> true);
        assertNotEquals("the first lease cannot act on the region any more", BotLeaseGuard.Phase.VALID, s.guard.phaseOf(first));
        if (why != null) {
            assertTrue(why, why.startsWith("region-entry: not reserved"));
        }
        assertFalse("the first lease never passed an entry check", s.logged("re-verified (loaded, lease " + first));
    }

    @Test
    public void actionsOutsideTheBoxAndNonRegionLeasesAreNotChecked() throws Exception {
        LeaseSupport s = startedRegionLease();
        assertNull(s.guard.regionEntry(LeaseFence.Kind.BREAK, 'o', 50, 10, 50, k -> false));
        assertNull("other dimension", s.guard.regionEntry(LeaseFence.Kind.BREAK, 'n', 5, 10, 5, k -> false));
        assertNull("a container action is not an entry", s.guard.regionEntry(LeaseFence.Kind.CONTAINER, 'o', 5, 10, 5, k -> false));
        assertFalse(s.logged("region-entry:"));

        LeaseSupport v = new LeaseSupport(tmp.newFolder().toPath());
        v.activate();
        v.coord.lease("Bob", "v:o:0,10,0:1,11,1", "mine iron_ore");
        v.pump();
        assertNull("a vein lease has no region entry", v.guard.regionEntry(LeaseFence.Kind.BREAK, 'o', 0, 10, 0, k -> false));
    }

    @Test
    public void boxHeldNeedsEveryChunk() {
        assertTrue(baritone.swarm.world.LoadedWorld.boxHeld(0, 0, 31, 15, (cx, cz) -> true));
        assertFalse(baritone.swarm.world.LoadedWorld.boxHeld(0, 0, 31, 15, (cx, cz) -> cx == 0));
        assertTrue(baritone.swarm.world.LoadedWorld.boxHeld(0, 0, 15, 15, (cx, cz) -> cx == 0 && cz == 0));
    }
}
