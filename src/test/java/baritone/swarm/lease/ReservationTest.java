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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/** Ostinato: Phase 2b reservations (typed geometric lease keys). */
public class ReservationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String VEIN = "v:o:-12,11,40:-10,13,42";

    private LeaseSupport active() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        return s;
    }

    // ---- keys ----

    @Test
    public void keysAreCanonical() {
        assertEquals(VEIN, ReservationKey.vein('o', -10, 13, 42, -12, 11, 40).toString());
        assertNotNull(ReservationKey.parse(VEIN));
        assertNull("non-canonical corner order", ReservationKey.parse("v:o:-10,13,42:-12,11,40"));
        assertNull("leading zero", ReservationKey.parse("v:o:-12,011,40:-10,13,42"));
        assertNull("bad dim", ReservationKey.parse("v:x:1,2,3:4,5,6"));
        assertNull("edge over 64", ReservationKey.parse("r:o:0,0,0:64,0,0"));
        assertNotNull(ReservationKey.parse("r:o:0,0,0:63,0,0"));
        assertNull("untyped", ReservationKey.parse("ore1"));
        assertEquals("c:o:5,64,7", ReservationKey.chest('o', 5, 64, 7).toString());
        assertEquals(ReservationKey.doubleChest('o', 6, 64, 7, 5, 64, 7).toString(),
                ReservationKey.doubleChest('o', 5, 64, 7, 6, 64, 7).toString());
        assertNull("chest three wide", ReservationKey.parse("c:o:5,64,7:7,64,7"));
    }

    @Test
    public void conflictsMeansOverlapInOneDimension() {
        assertTrue(ReservationKey.conflicts(VEIN, "r:o:-10,13,42:0,20,50"));
        assertFalse(ReservationKey.conflicts(VEIN, "r:o:-9,13,42:0,20,50"));
        assertFalse("other dimension", ReservationKey.conflicts(VEIN, "v:n:-12,11,40:-10,13,42"));
        assertTrue(ReservationKey.conflicts("ore1", "ore1"));
        assertFalse(ReservationKey.conflicts("ore1", VEIN));
    }

    // ---- resolver ----

    private static ReservationResolver.World world(Map<String, String> blocks, int loadedRadius) {
        return new ReservationResolver.World() {
            @Override
            public String block(int x, int y, int z) {
                if (Math.abs(x) > loadedRadius || Math.abs(z) > loadedRadius) {
                    return null;
                }
                return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:stone");
            }

            @Override
            public int[] otherChestHalf(int x, int y, int z) {
                if (x == 5 && y == 64 && z == 7) {
                    return new int[]{6, 64, 7};
                }
                if (x == 6 && y == 64 && z == 7) {
                    return new int[]{5, 64, 7};
                }
                return null;
            }
        };
    }

    private static Map<String, String> lVein() {
        Map<String, String> m = new HashMap<>();
        m.put("0,10,0", "minecraft:iron_ore");
        m.put("1,10,0", "minecraft:iron_ore");
        m.put("1,11,0", "minecraft:iron_ore");
        m.put("1,11,1", "minecraft:iron_ore");
        m.put("2,10,0", "minecraft:coal_ore"); // other ore: not part of the vein
        return m;
    }

    @Test
    public void veinBoxIsTheSameFromAnySeed() {
        ReservationResolver.World w = world(lVein(), 100);
        String a = ReservationResolver.resolve("vein:0,10,0", 'o', w).key;
        assertEquals("v:o:0,10,0:1,11,1", a);
        assertEquals(a, ReservationResolver.resolve("vein:1,11,1", 'o', w).key);
        assertEquals(a, ReservationResolver.resolve("vein:1,10,0", 'o', w).key);
        assertNotNull(ReservationResolver.resolve("vein:3,10,0", 'o', w).error); // stone
    }

    @Test
    public void veinRejectsUnloadedAndOversize() {
        Map<String, String> m = lVein();
        assertNotNull(ReservationResolver.resolve("vein:0,10,0", 'o', world(m, 0)).error);
        assertNotNull(ReservationResolver.resolve("vein:50,10,50", 'o', world(m, 10)).error);
        Map<String, String> big = new HashMap<>();
        for (int i = 0; i < 65; i++) {
            big.put(i % 40 + ",20," + i / 40, "minecraft:gold_ore");
        }
        ReservationResolver.Result r = ReservationResolver.resolve("vein:0,20,0", 'o', world(big, 100));
        assertNull(r.key);
        assertTrue(r.error, r.error.contains("over"));
    }

    @Test
    public void doubleChestEitherHalfGivesOneKey() {
        ReservationResolver.World w = world(new HashMap<>(), 100);
        String a = ReservationResolver.resolve("chest:6,64,7", 'o', w).key;
        assertEquals("c:o:5,64,7:6,64,7", a);
        assertEquals(a, ReservationResolver.resolve("chest:5,64,7", 'o', w).key);
        assertEquals("c:o:9,64,9", ReservationResolver.resolve("chest:9,64,9", 'o', w).key);
        assertEquals("ore1", ReservationResolver.resolve("ore1", 'o', w).key);
    }

    // ---- coordinator ----

    @Test
    public void overlappingReservationIsBusy() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", VEIN, "mine iron_ore");
        assertTrue(r, r.contains("gen 1"));
        s.pump();
        String other = s.coord.lease("Bob", "r:o:-10,13,42:0,20,50", "mine x");
        assertTrue(other, other.contains("busy") && other.contains("via " + VEIN));
        String apart = s.coord.lease("Bob", "r:o:-9,13,42:0,20,50", "mine x");
        assertFalse(apart, apart.contains("busy"));
    }

    @Test
    public void malformedTypedKeyIsRejected() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", "v:o:-10,13,42:-12,11,40", "mine x");
        assertTrue(r, r.contains("bad reservation key"));
    }

    @Test
    public void restartKeepsReportedBoxAndBlocksOverlap() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", VEIN, "mine iron_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        s.coord = s.newCoordinator("Alice", "Alice");
        s.coord.start();
        s.pump();
        s.run(8_000L, 1_000L);
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        assertEquals(LeaseRecord.State.GRANTED, s.coord.record(id).state);
        String other = s.coord.lease("Bob", "r:o:-11,12,41:-11,12,41", "mine x");
        assertTrue(other, other.contains("busy"));
    }

    /** A claim above high-water on an overlapping box poisons the reported holder too: neither is kept. */
    @Test
    public void overlappingPoisonedClaimRevokesTheHolder() throws Exception {
        LeaseSupport s = active();
        UUID bob = LeaseSupport.leaseIdOf(s.coord.lease("Bob", VEIN, "mine iron_ore"));
        s.pump();
        UUID carol = s.addHolder("Carol", "ore9", 1, LeaseRecord.State.GRANTED, s.wall.get() + 20_000L);
        s.coord = s.newCoordinator("Alice", "Alice");
        s.coord.start();
        s.pump();
        assertTrue(s.logged("reconcile-report bot=Bob"));
        String claim = "phase=valid,exec=running,obs=running,reason=none,active=true";
        s.coord.onMessage("Carol", new LeaseMessage(LeaseMessage.Op.REPORT, UUID.randomUUID(),
                "r:o:-11,12,41:-11,12,41", 5, UUID.randomUUID(), 0, 0, claim));
        s.coord.onMessage("Carol", new LeaseMessage(LeaseMessage.Op.REPORT, carol, "ore9", 1,
                s.coord.record(carol).commandId, 0, 0, claim));
        s.coord.onMessage("Carol", new LeaseMessage(LeaseMessage.Op.REPORT, null, "-", 0, null, 0, 0, "end"));
        s.pump();
        s.run(8_000L, 1_000L);
        assertEquals(LeaseCoordinator.Mode.ACTIVE, s.coord.mode());
        assertFalse(s.logged("kept lease=" + bob));
        assertNotEquals(LeaseRecord.State.GRANTED, s.coord.record(bob).state);
        assertTrue("control: Carol's own clean claim is kept", s.logged("kept lease=" + carol));
    }

    @Test
    public void killedHolderBoxRegrantsOnlyAfterExpiry() throws Exception {
        LeaseSupport s = active();
        String r = s.coord.lease("Bob", VEIN, "mine iron_ore");
        s.pump();
        UUID id = LeaseSupport.leaseIdOf(r);
        s.dropTo.add("Bob");
        s.dropTo.add("Alice");
        s.aliceP.onPeerDisconnect("Bob");
        s.run(30_000L, 1_000L);
        assertTrue(s.coord.lease("Bob", "r:o:-12,11,40:-12,11,40", "mine x").contains("busy"));
        s.run(4_000L, 1_000L);
        assertEquals(LeaseRecord.State.EXPIRED, s.coord.record(id).state);
        s.relaunchBob();
        s.bobExec.byName.clear();
        s.dropTo.clear();
        String again = s.coord.lease("Bob", VEIN, "mine iron_ore");
        assertTrue(again, again.contains("gen 2"));
    }

    // ---- bot-side geometry ----

    @Test
    public void guardGeometryFollowsTheKeyType() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", VEIN, "mine iron_ore");
        s.pump();
        BotLeaseGuard g = s.guard;
        LeaseFence.Kind B = LeaseFence.Kind.BREAK;
        assertFalse(g.outsideReservation(B, 'o', -11, 12, 41, true));
        assertTrue("ore outside the vein", g.outsideReservation(B, 'o', -20, 12, 41, true));
        assertFalse("stone on the path", g.outsideReservation(B, 'o', -20, 12, 41, false));
        assertTrue("same box, other dim", g.outsideReservation(B, 'n', -11, 12, 41, true));
        assertFalse("vein does not fence placing", g.outsideReservation(LeaseFence.Kind.PLACE, 'o', 99, 1, 1, false));
        assertFalse("vein does not fence chests", g.outsideReservation(LeaseFence.Kind.CONTAINER, 'o', 99, 1, 1, false));
    }

    @Test
    public void untypedLeaseHasNoGeometry() throws Exception {
        LeaseSupport s = active();
        s.coord.lease("Bob", "ore1", "mine iron_ore");
        s.pump();
        assertFalse(s.guard.outsideReservation(LeaseFence.Kind.BREAK, 'o', 99, 1, 1, true));
    }

    @Test
    public void fenceDeniesOutsideGeometry() {
        try {
            LeaseFence.installGeometry((k, x, y, z) -> x > 10);
            assertTrue(LeaseFence.denied(LeaseFence.Kind.BREAK, 11, 0, 0));
            assertFalse(LeaseFence.denied(LeaseFence.Kind.BREAK, 10, 0, 0));
        } finally {
            LeaseFence.clear();
        }
    }
}
