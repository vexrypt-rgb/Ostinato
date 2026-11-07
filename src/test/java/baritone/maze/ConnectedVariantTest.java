package baritone.maze;

import baritone.api.maze.MazeFurnishJob.Step;
import baritone.maze.BlockProbe.BlockKind;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static baritone.maze.ConnectedPlan.key;
import static baritone.maze.ConnectedPlan.offset;
import static baritone.maze.ConnectedPlan.x;
import static baritone.maze.ConnectedPlan.y;
import static baritone.maze.ConnectedPlan.z;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Ostinato: 2026-11-02, backrooms variants. Connected-mode room shapes, grown in a world of solid stone. */
public class ConnectedVariantTest {

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final BlockProbe STONE = (x, y, z) -> BlockKind.SOLID;

    /** A forever run: the first room, then {@code n - 1} more through {@link ConnectedPlan#next}. Rings kept per tunnel. */
    private static ConnectedPlan.Complex grow(long seed, int n, Set<Long> rings) {
        ConnectedPlan.First first = ConnectedPlan.firstRoom(seed, 0, 0, 40, -64, 320, STONE, new ArrayList<>());
        assertNotNull(first);
        ConnectedPlan.Complex c = first.complex();
        c.put(ConnectedPlan.first(c, 0, 0, first.originY()).room);
        for (int i = 1; i < n; i++) {
            ConnectedPlan.Choice ch = ConnectedPlan.next(c, STONE, (x, z) -> 64, -64, 320, 64);
            assertTrue("no choice at room " + i + ": " + ch.why(), ch.parentDir() >= 0);
            int span = ch.span();
            ConnectedPlan.Room parent = c.room(ch.gx() - ConnectedPlan.dx(ch.parentDir()) * span, ch.gz() - ConnectedPlan.dz(ch.parentDir()) * span);
            assertNotNull(parent);
            ConnectedPlan.Segment s = ConnectedPlan.join(c, c.air(), parent, ch.parentDir(), ch.originY(), span);
            assertTrue(ConnectedPlan.breaches(s, STONE, false).isEmpty());
            rings.addAll(s.ring);
            c = s.after;
        }
        return c;
    }

    @Test
    public void everyVariantFitsItsCellAndTheDoorLane() {
        for (RoomVariant v : RoomVariant.values()) {
            assertTrue(v.id + " clear height", v.clearHeight() >= 4);
            assertTrue(v.id + " fits", v.sizeY <= ConnectedPlan.MAX_HEIGHT);
            // interior (1..size-2) holds the door columns, cell-relative 2..4
            assertTrue(v.id + " lane x", v.offX + 1 <= 2 && v.offX + v.sizeX - 2 >= 4);
            assertTrue(v.id + " lane z", v.offZ + 1 <= 2 && v.offZ + v.sizeZ - 2 >= 4);
            for (RoomVariant w : RoomVariant.values()) {
                assertTrue(v.id + "->" + w.id + " run", ConnectedPlan.runBetween(v, w, ConnectedPlan.EAST) >= 3);
                assertTrue(v.id + "->" + w.id + " run", ConnectedPlan.runBetween(v, w, ConnectedPlan.SOUTH) >= 3);
            }
        }
    }

    @Test
    public void classicIsTheOldFixedBox() {
        long seed = 71006;
        for (int doors = 0; doors < 16; doors++) {
            RoomPlan now = ConnectedPlan.layout(seed, 3, -2, 48, 30, -32, doors);
            RoomPlan old = ConnectedPlan.legacyLayout(seed, 3, -2, 48, 30, -32, doors);
            assertEquals(old.fingerprint(), now.fingerprint());
        }
    }

    @Test
    public void foreverExpandPicksManyVariants() {
        for (long seed : new long[]{0, 71006, 12345}) {
            ConnectedPlan.Complex c = grow(seed, 30, new HashSet<>());
            Set<String> combos = new HashSet<>();
            for (ConnectedPlan.Room r : c.rooms()) {
                RoomPlan p = c.plan(r);
                combos.add(r.variant().id + "/" + p.theme.id + "/" + p.sizeX + "x" + p.sizeY + "x" + p.sizeZ);
                assertFalse(r.variant() == RoomVariant.CLASSIC);
            }
            assertTrue("seed " + seed + " only " + combos, combos.size() >= 6);
        }
    }

    @Test
    public void growthIsSeedDeterministicAndSavesItsVariants() {
        List<String> a = ConnectedPlan.save(grow(71006, 20, new HashSet<>()));
        List<String> b = ConnectedPlan.save(grow(71006, 20, new HashSet<>()));
        assertEquals(a, b);
        ConnectedPlan.Complex back = ConnectedPlan.load(a);
        assertEquals(a, ConnectedPlan.save(back));
        // a map written before variants: five fields, read as CLASSIC
        ConnectedPlan.Complex legacy = ConnectedPlan.load(List.of(ConnectedPlan.MAP_HEADER, "seed 5", "anchor 0 0", "room 0 0 30 1"));
        assertEquals(RoomVariant.CLASSIC, legacy.room(0, 0).variant());
        assertEquals(7, legacy.plan(legacy.room(0, 0)).sizeX);
    }

    @Test
    public void clearHeightIsAtLeastFourEverywhere() {
        ConnectedPlan.Complex c = grow(71006, 30, new HashSet<>());
        for (ConnectedPlan.Room r : c.rooms()) {
            RoomPlan p = c.plan(r);
            for (int x = 0; x < p.sizeX; x++) {
                for (int z = 0; z < p.sizeZ; z++) {
                    if (p.role(x, 2, z) != BlockRole.AIR) continue;
                    int clear = 0;
                    for (int y = 2; y < p.sizeY && p.role(x, y, z) == BlockRole.AIR; y++) clear++;
                    boolean door = x == 0 || z == 0 || x == p.sizeX - 1 || z == p.sizeZ - 1;
                    assertTrue(r.variant().id + " at " + x + "," + z + " clear " + clear, clear >= (door ? 3 : 4));
                }
            }
        }
    }

    @Test
    public void carvedSpaceIsSealedByPlannedSolids() {
        Set<Long> rings = new HashSet<>();
        ConnectedPlan.Complex c = grow(12345, 30, rings);
        Set<Long> air = c.air();
        for (long k : air) {
            for (int[] f : FACES) {
                long n = offset(k, f[0], f[1], f[2]);
                if (air.contains(n)) continue;
                BlockRole role = c.roleAt(n);
                boolean planned = role != null && role != BlockRole.OUTSIDE && role.solid() || rings.contains(n);
                assertTrue("air " + x(k) + "," + y(k) + "," + z(k) + " opens onto " + x(n) + "," + y(n) + "," + z(n), planned);
            }
        }
    }

    @Test
    public void caveInAnLsCutCornerIsABreach() {
        long seed = 0;
        int gx = 0;
        while (ConnectedPlan.variant(seed, gx, 0) != RoomVariant.L_OFFICE) gx++;
        ConnectedPlan.Complex at = new ConnectedPlan.Complex(seed, -gx * ConnectedPlan.PITCH, 0);
        ConnectedPlan.Segment s = ConnectedPlan.first(at, gx, 0, 30);
        RoomPlan p = s.plan;
        assertEquals(BlockRole.OUTSIDE, p.role(8, 3, 8));
        assertEquals(BlockRole.WALL, p.role(6, 3, 8));
        // a 1-block cave pocket in the cut corner, right behind the notch wall
        int cx = p.originX + 7, cy = p.originY + 3, cz = p.originZ + 8;
        BlockProbe cave = (x, y, z) -> x == cx && y == cy && z == cz ? BlockKind.AIR : BlockKind.SOLID;
        assertFalse(ConnectedPlan.breaches(s, cave, false).isEmpty());
        assertTrue(ConnectedPlan.breaches(s, STONE, false).isEmpty());
    }

    @Test
    public void everyRoomIsReachableOnFoot() {
        ConnectedPlan.Complex c = grow(71006, 30, new HashSet<>());
        Set<Long> air = c.air();
        Set<Long> containers = c.containers();
        Set<Long> feet = new HashSet<>();
        for (long k : air) {
            long below = offset(k, 0, -1, 0);
            if (!air.contains(below) && !containers.contains(k) && !containers.contains(below) && air.contains(offset(k, 0, 1, 0))) feet.add(k);
        }
        ConnectedPlan.Room first = c.rooms().get(0);
        long start = -1;
        for (long k : feet) if (c.roomAt(x(k), y(k), z(k)) == first) { start = k; break; }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty()) {
            long k = q.poll();
            for (int[] d : ConnectedPlan.DIR) {
                for (int dy = -1; dy <= 1; dy++) {
                    long n = offset(k, d[0], dy, d[1]);
                    if (!feet.contains(n) || seen.contains(n)) continue;
                    if (dy == 1 && !air.contains(offset(k, 0, 2, 0))) continue; // jump headroom
                    if (dy == -1 && !air.contains(offset(n, 0, 2, 0))) continue;
                    seen.add(n);
                    q.add(n);
                }
            }
        }
        Map<ConnectedPlan.Room, Integer> reached = new HashMap<>();
        for (long k : seen) {
            ConnectedPlan.Room r = c.roomAt(x(k), y(k), z(k));
            if (r != null) reached.merge(r, 1, Integer::sum);
        }
        for (ConnectedPlan.Room r : c.rooms()) {
            assertTrue(r.variant().id + " at " + r.gx() + "," + r.gz() + " unreachable", reached.containsKey(c.room(r.gx(), r.gz())));
        }
        // and every door opens onto the tunnel beside its wall
        for (ConnectedPlan.Room r : c.rooms()) {
            RoomPlan p = c.plan(r);
            for (int dir = 0; dir < 4; dir++) {
                if (!r.door(dir)) continue;
                int[] col = r.variant().doorColumn(dir, RoomVariant.LANE);
                long door = key(p.originX + col[0], p.originY + 2, p.originZ + col[1]);
                assertTrue(air.contains(door));
                assertTrue(r.variant().id + " door " + dir, air.contains(offset(door, ConnectedPlan.dx(dir), 0, ConnectedPlan.dz(dir)))
                        || air.contains(offset(door, ConnectedPlan.dx(dir), 1, ConnectedPlan.dz(dir)))
                        || air.contains(offset(door, ConnectedPlan.dx(dir), -1, ConnectedPlan.dz(dir))));
            }
        }
    }

    @Test
    public void bordersBlendTheNeighboursPalette() {
        ConnectedPlan.Complex c = grow(71006, 30, new HashSet<>());
        int blended = 0;
        // rebuild each join and look for a clad step whose first id is the parent's
        ConnectedPlan.Complex g = new ConnectedPlan.Complex(c.seed, c.anchorX, c.anchorZ);
        for (ConnectedPlan.Room r : c.rooms()) {
            if (g.isEmpty()) {
                g.put(new ConnectedPlan.Room(r.gx(), r.gz(), r.originY(), 0, r.variant()));
                continue;
            }
            for (int dir = 0; dir < 4; dir++) {
                ConnectedPlan.Room parent = g.room(r.gx() - ConnectedPlan.dx(dir), r.gz() - ConnectedPlan.dz(dir));
                if (parent == null || !r.door(ConnectedPlan.opposite(dir))) continue;
                ConnectedPlan.Segment s = ConnectedPlan.join(g, g.air(), parent, dir, r.originY());
                MazeTheme pt = s.after.plan(s.parent).theme;
                if (pt != s.plan.theme) {
                    for (Step st : ConnectedPlan.cladding(s)) {
                        if (s.after.roomAt(st.x(), st.y(), st.z()) == s.room
                                && !MazeFurnishPlanner.cladIds(s.plan.theme, BlockRole.WALL, s.plan.originY).get(0).equals(st.blockIds().get(0))
                                && !MazeFurnishPlanner.cladIds(s.plan.theme, BlockRole.FLOOR, s.plan.originY).get(0).equals(st.blockIds().get(0))
                                && !MazeFurnishPlanner.cladIds(s.plan.theme, BlockRole.CEILING, s.plan.originY).get(0).equals(st.blockIds().get(0))) {
                            blended++;
                        }
                    }
                }
                g = s.after;
                break;
            }
        }
        assertTrue("no blended border cell", blended > 0);
    }

    @Test
    public void newThemesHaveCladdingAndLoot() {
        for (MazeTheme t : MazeTheme.values()) {
            for (BlockRole role : new BlockRole[]{BlockRole.FLOOR, BlockRole.WALL, BlockRole.CEILING}) {
                assertFalse(MazeFurnishPlanner.cladIds(t, role, 40).isEmpty());
            }
            for (LootPlanner.ContainerKind k : LootPlanner.ContainerKind.values()) {
                assertFalse(LootPlanner.table(t, k).isEmpty());
            }
        }
    }

    @Test
    public void roomLineHasTheHarnessFields() {
        ConnectedPlan.Complex c = new ConnectedPlan.Complex(1, 0, 0);
        ConnectedPlan.Room r = new ConnectedPlan.Room(2, -1, 30, 0, RoomVariant.LOBBY);
        String line = c.plan(r).roomLine("dig");
        assertEquals("MAZE ROOM variant=lobby theme=lobby size=11x9x11 cell=2,-1 phase=dig", line);
    }

    /** Ostinato: run111. A dark cell with no sturdy floor still gets a wall torch, and a missed mount stays dark. */
    @Test
    public void repairMountsPlansATorchBesideEachDarkCell() {
        Set<Long> open = new HashSet<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                open.add(key(x, 2, z));
                open.add(key(x, 3, z));
            }
        }
        long floorDark = key(1, 2, 1);
        long wallDark = key(0, 3, 0); // open underneath, solid to the west
        // Only the one floor cell is accepted, so the high cell cannot borrow a neighbour's floor and takes a wall torch.
        java.util.function.LongPredicate floor = k -> k == floorDark && ConnectedLight.repairFloor(open, k, below -> true);
        java.util.function.LongPredicate wall = k -> ConnectedLight.repairWall(open, k);
        assertTrue(floor.test(floorDark));
        assertFalse(floor.test(wallDark));
        assertTrue(wall.test(wallDark));
        List<Long> dark = List.of(floorDark, wallDark);
        List<ConnectedLight.Mount> mounts = ConnectedLight.repairMounts(open, dark, floor, wall);
        assertEquals(0, ConnectedLight.stillDark(open, mounts, dark));
        boolean sawFloor = false, sawWall = false;
        for (ConnectedLight.Mount m : mounts) {
            if (m.cell() == floorDark) {
                assertFalse(m.wall());
                sawFloor = true;
            }
            if (m.cell() == wallDark) {
                assertTrue(m.wall());
                sawWall = true;
            }
        }
        assertTrue(sawFloor);
        assertTrue(sawWall);
        List<ConnectedLight.Mount> none = ConnectedLight.repairMounts(open, List.of(wallDark), k -> false, k -> false);
        assertTrue(none.isEmpty());
        assertEquals(1, ConnectedLight.stillDark(open, none, List.of(wallDark)));
    }

    /**
     * Ostinato: run111. maintenance@1,0 and the office left three spawn cells at block light 0. The reach model treats
     * them as lit, and {@link ConnectedPlan#mountable} refuses the feature under one of them. A repair torch still goes
     * on that sturdy top.
     */
    @Test
    public void repairMountsTheRun111DarkCells() {
        ConnectedPlan.Complex c = new ConnectedPlan.Complex(0, -38, -50);
        c.put(new ConnectedPlan.Room(0, 0, 95, 1, RoomVariant.SMALL_OFFICE));
        c.put(new ConnectedPlan.Room(1, 0, 88, 4, RoomVariant.MAINTENANCE));
        Set<Long> open = ConnectedLight.open(c.air(), c.containers());
        long aboveFeature = key(-21, 91, -41);
        long maintenance = key(-21, 90, -42);
        long office = key(-35, 97, -46);
        assertTrue(open.contains(aboveFeature));
        assertTrue(open.contains(maintenance));
        assertTrue(open.contains(office));
        assertEquals(BlockRole.FEATURE, c.roleAt(offset(aboveFeature, 0, -1, 0)));
        assertFalse(ConnectedPlan.mountable(c, open, c.containers(), aboveFeature));
        java.util.function.LongPredicate floor = k -> ConnectedLight.repairFloor(open, k, below -> c.roleAt(below) != BlockRole.WATER);
        java.util.function.LongPredicate wall = k -> ConnectedLight.repairWall(open, k);
        assertTrue(floor.test(aboveFeature));
        assertTrue(floor.test(maintenance));
        assertTrue(floor.test(office));
        List<Long> dark = List.of(aboveFeature, maintenance, office);
        // The two torches the furnish actually stood. The maintenance one sits in the tunnel, just outside the door.
        List<Long> standing = List.of(key(-35, 97, -47), key(-23, 90, -47));
        Set<Long> withTorches = new HashSet<>(open);
        withTorches.addAll(standing);
        ConnectedLight.Result model = ConnectedLight.plan(withTorches, standing,
                k -> ConnectedPlan.mountable(c, withTorches, c.containers(), k), k -> ConnectedPlan.preferredTorch(c, k));
        assertTrue(model.torches().isEmpty());
        assertTrue(model.dark().isEmpty());
        List<ConnectedLight.Mount> mounts = ConnectedLight.repairMounts(open, dark, floor, wall);
        assertFalse(mounts.isEmpty());
        assertEquals(0, ConnectedLight.stillDark(open, mounts, dark));
        for (ConnectedLight.Mount m : mounts) {
            if (m.cell() == aboveFeature || m.cell() == maintenance || m.cell() == office) assertFalse(m.wall());
        }
    }

    /** Ostinato: run111. Every neighbour already marked failed is probed again, instead of reporting none free. */
    @Test
    public void failedFrontierIsRetried() {
        ConnectedPlan.Complex c = grow(0, 2, new HashSet<>());
        int marks = 0;
        for (ConnectedPlan.Room parent : c.rooms()) {
            for (int dir = 0; dir < 4; dir++) {
                int gx = parent.gx() + ConnectedPlan.dx(dir), gz = parent.gz() + ConnectedPlan.dz(dir);
                if (c.room(gx, gz) != null) continue;
                c.fail(gx, gz);
                marks++;
            }
        }
        assertTrue(marks >= 4);
        ConnectedPlan.Choice ch = ConnectedPlan.next(c, STONE, (x, z) -> 64, -64, 320, 12);
        assertTrue(ch.why() + " tried=" + ch.tried(), ch.parentDir() >= 0);
        assertTrue(ch.tried() > 0);
    }

    /** Ostinato: run111. Newest-room neighbours that are caves give way to an older room's neighbours. */
    @Test
    public void expansionFallsBackToOlderRooms() {
        ConnectedPlan.Complex c = grow(0, 2, new HashSet<>());
        ConnectedPlan.Room newest = c.rooms().get(c.rooms().size() - 1);
        List<int[]> bad = new ArrayList<>();
        List<int[]> good = new ArrayList<>();
        for (ConnectedPlan.Room parent : c.rooms()) {
            for (int dir = 0; dir < 4; dir++) {
                int gx = parent.gx() + ConnectedPlan.dx(dir), gz = parent.gz() + ConnectedPlan.dz(dir);
                if (c.room(gx, gz) != null) continue;
                (parent.equals(newest) ? bad : good).add(new int[]{gx, gz});
            }
        }
        assertFalse(bad.isEmpty());
        assertFalse(good.isEmpty());
        for (int[] g : good) c.fail(g[0], g[1]);
        BlockProbe carved = (x, y, z) -> {
            for (int[] b : bad) {
                int x0 = c.originX(b[0]), z0 = c.originZ(b[1]);
                if (x >= x0 && x < x0 + ConnectedPlan.PITCH && z >= z0 && z < z0 + ConnectedPlan.PITCH) return BlockKind.AIR;
            }
            return BlockKind.SOLID;
        };
        ConnectedPlan.Choice ch = ConnectedPlan.next(c, carved, (x, z) -> 64, -64, 320, 12);
        assertTrue(ch.why(), ch.parentDir() >= 0);
        boolean forgiven = false;
        for (int[] g : good) if (g[0] == ch.gx() && g[1] == ch.gz()) forgiven = true;
        assertTrue("chose " + ch.gx() + "," + ch.gz() + " " + ch.why(), forgiven);
    }

    /** Ostinato: run111. Unknown stone beside a loaded centre is not saved as a permanent no-seal. */
    @Test
    public void unloadedBreachIsNotRemembered() {
        ConnectedPlan.Complex c = grow(0, 1, new HashSet<>());
        int pref = ConnectedPlan.preferredY(64, -64, 320);
        BlockProbe probe = (x, y, z) -> {
            for (ConnectedPlan.Room parent : c.rooms()) {
                for (int dir = 0; dir < 4; dir++) {
                    int gx = parent.gx() + ConnectedPlan.dx(dir), gz = parent.gz() + ConnectedPlan.dz(dir);
                    if (x == c.originX(gx) + 3 && y == pref && z == c.originZ(gz) + 3) return BlockKind.SOLID;
                }
            }
            return BlockKind.UNKNOWN;
        };
        ConnectedPlan.Choice ch = ConnectedPlan.next(c, probe, (x, z) -> 64, -64, 320, 12);
        assertTrue(ch.parentDir() < 0);
        assertEquals(0, c.failedCells().size());
        assertTrue(ch.why(), ch.why().contains("unloaded"));
        assertFalse(ch.why().contains("none"));
    }

    private static ConnectedPlan.Complex oneRoom() {
        ConnectedPlan.First first = ConnectedPlan.firstRoom(0, 0, 0, 40, -64, 320, STONE, new ArrayList<>());
        assertNotNull(first);
        ConnectedPlan.Complex c = first.complex();
        c.put(ConnectedPlan.first(c, 0, 0, first.originY()).room);
        return c;
    }

    /** Air filling a neighbour pitch. {@code fluidWest} makes the west pitch fluid instead, so the log names both. */
    private static BlockProbe neighbourFill(ConnectedPlan.Complex c, BlockKind fill, boolean fluidWest) {
        ConnectedPlan.Room parent = c.rooms().get(0);
        return (x, y, z) -> {
            for (int dir = 0; dir < 4; dir++) {
                int gx = parent.gx() + ConnectedPlan.dx(dir), gz = parent.gz() + ConnectedPlan.dz(dir);
                int x0 = c.originX(gx), z0 = c.originZ(gz);
                if (x >= x0 && x < x0 + ConnectedPlan.PITCH && z >= z0 && z < z0 + ConnectedPlan.PITCH) {
                    if (fluidWest && dir == ConnectedPlan.WEST) return BlockKind.FLUID;
                    return fill;
                }
            }
            return BlockKind.SOLID;
        };
    }

    /**
     * Ostinato: run112. Every adjacent pitch is open (one of them fluid). The log names the first breach and the depth
     * window, and the next room is a farther cell reached by a corridor through the blocked neighbour.
     */
    @Test
    public void breachNamesKindAndDepthAndCrossesToAFartherCell() {
        ConnectedPlan.Complex c = oneRoom();
        BlockProbe world = neighbourFill(c, BlockKind.AIR, true);
        ConnectedPlan.Choice ch = ConnectedPlan.next(c, world, (x, z) -> 64, -64, 320, 12);
        assertTrue(ch.why(), ch.parentDir() >= 0);
        assertTrue("span " + ch.span() + " " + ch.why(), ch.span() >= 2);
        assertTrue(ch.why(), ch.why().contains(":AIR"));
        assertTrue(ch.why(), ch.why().contains(":FLUID"));
        assertTrue(ch.why(), ch.why().matches("(?s).* -?\\d+,-?\\d+,-?\\d+:AIR depths=-?\\d+\\.\\.-?\\d+.*"));
        assertTrue(ch.why(), ch.why().matches("(?s).* -?\\d+,-?\\d+,-?\\d+:FLUID depths=-?\\d+\\.\\.-?\\d+.*"));
        ConnectedPlan.Room parent = c.room(ch.gx() - ConnectedPlan.dx(ch.parentDir()) * ch.span(),
                ch.gz() - ConnectedPlan.dz(ch.parentDir()) * ch.span());
        assertNotNull(parent);
        assertEquals(ch.span(), Math.abs(ch.gx() - parent.gx()) + Math.abs(ch.gz() - parent.gz()));
        // The west pitch is fluid all the way down, so the corridor must leave through some other door.
        assertFalse(ch.gx() == parent.gx() - ch.span() && ch.gz() == parent.gz());
        ConnectedPlan.Segment s = ConnectedPlan.join(c, c.air(), parent, ch.parentDir(), ch.originY(), ch.span());
        assertFalse(ConnectedPlan.breaches(s, world, false).isEmpty());
        assertTrue(ConnectedPlan.buildable(s, world));
        assertEquals(1, s.after.tunnels().size());
        assertEquals(s.tunnel, new HashSet<>(s.after.tunnels().get(0)));
        assertTrue(ConnectedPlan.maxRise(RoomVariant.SMALL_OFFICE, RoomVariant.SMALL_OFFICE, ConnectedPlan.EAST, 2) > ConnectedPlan.MAX_RISE);
    }

    /**
     * Ostinato: run112. The neighbour pitches are carved air framed by our own blocks (a prior run in this world).
     * That is joined in place instead of being a permanent no-seal.
     */
    @Test
    public void priorCarvedAirIsJoinedInPlace() {
        ConnectedPlan.Complex c = oneRoom();
        ConnectedPlan.Room parent = c.rooms().get(0);
        BlockProbe carved = new BlockProbe() {
            @Override
            public BlockKind get(int x, int y, int z) {
                for (int dir = 0; dir < 4; dir++) {
                    int gx = parent.gx() + ConnectedPlan.dx(dir), gz = parent.gz() + ConnectedPlan.dz(dir);
                    int x0 = c.originX(gx), z0 = c.originZ(gz);
                    if (x < x0 || x >= x0 + ConnectedPlan.PITCH || z < z0 || z >= z0 + ConnectedPlan.PITCH) continue;
                    boolean frame = x == x0 || x == x0 + ConnectedPlan.PITCH - 1 || z == z0 || z == z0 + ConnectedPlan.PITCH - 1;
                    return frame ? BlockKind.SOLID : BlockKind.AIR;
                }
                return BlockKind.SOLID;
            }

            @Override
            public boolean structure(int x, int y, int z) {
                for (int dir = 0; dir < 4; dir++) {
                    int gx = parent.gx() + ConnectedPlan.dx(dir), gz = parent.gz() + ConnectedPlan.dz(dir);
                    int x0 = c.originX(gx), z0 = c.originZ(gz);
                    if (x < x0 || x >= x0 + ConnectedPlan.PITCH || z < z0 || z >= z0 + ConnectedPlan.PITCH) continue;
                    return x == x0 || x == x0 + ConnectedPlan.PITCH - 1 || z == z0 || z == z0 + ConnectedPlan.PITCH - 1;
                }
                return false;
            }
        };
        ConnectedPlan.Choice ch = ConnectedPlan.next(c, carved, (x, z) -> 64, -64, 320, 12);
        assertTrue(ch.why(), ch.parentDir() >= 0);
        assertEquals(ch.why(), 1, ch.span());
        assertEquals(1, Math.abs(ch.gx() - parent.gx()) + Math.abs(ch.gz() - parent.gz()));
        ConnectedPlan.Segment s = ConnectedPlan.join(c, c.air(), parent, ch.parentDir(), ch.originY(), ch.span());
        assertFalse("the carved room should still touch the shell", ConnectedPlan.breaches(s, carved, false).isEmpty());
        assertTrue(ConnectedPlan.buildable(s, carved));
    }

    /**
     * Ostinato: run112. Adjacent cells are open around every depth a one-cell tunnel can stair, and the cell beyond
     * them is open in that same band. The longer corridor crosses the blocked neighbour and sets the new room past
     * {@link ConnectedPlan#MAX_RISE}, where that farther cell is solid.
     */
    @Test
    public void longerCorridorReachesPastMaxRise() {
        ConnectedPlan.Complex c = oneRoom();
        ConnectedPlan.Room parent = c.rooms().get(0);
        int span1 = 0, span2 = Integer.MAX_VALUE;
        for (int dir = 0; dir < 4; dir++) {
            int gx = parent.gx() + ConnectedPlan.dx(dir), gz = parent.gz() + ConnectedPlan.dz(dir);
            span1 = Math.max(span1, ConnectedPlan.stretchRise(parent.variant(), ConnectedPlan.variant(c.seed, gx, gz), dir, 1));
            int fx = parent.gx() + ConnectedPlan.dx(dir) * 2, fz = parent.gz() + ConnectedPlan.dz(dir) * 2;
            span2 = Math.min(span2, ConnectedPlan.stretchRise(parent.variant(), ConnectedPlan.variant(c.seed, fx, fz), dir, 2));
        }
        // Taller than a room, and past every adjacent stair, but still inside the two-cell stair.
        int band = Math.max(span1, ConnectedPlan.MAX_HEIGHT) + 1;
        assertTrue("span2 climb " + span2 + " band " + band, span2 > band);
        int py = parent.originY();
        BlockProbe banded = (x, y, z) -> {
            int gx = Math.floorDiv(x - c.anchorX, ConnectedPlan.PITCH);
            int gz = Math.floorDiv(z - c.anchorZ, ConnectedPlan.PITCH);
            int manhattan = Math.abs(gx - parent.gx()) + Math.abs(gz - parent.gz());
            if (manhattan == 1) return BlockKind.AIR; // blocked neighbour, every depth
            if (manhattan == 2 && (gx == parent.gx() || gz == parent.gz())) {
                if (Math.abs(y - py) <= band) return BlockKind.AIR;
            }
            return BlockKind.SOLID;
        };
        ConnectedPlan.Choice ch = ConnectedPlan.next(c, banded, (x, z) -> 64, -64, 320, 16);
        assertTrue(ch.why(), ch.parentDir() >= 0);
        assertTrue("span " + ch.span() + " " + ch.why(), ch.span() >= 2);
        int capped = ConnectedPlan.maxRise(parent.variant(), ConnectedPlan.variant(c.seed, ch.gx(), ch.gz()), ch.parentDir(), 1);
        assertTrue("rise " + (ch.originY() - py) + " cap " + capped + " " + ch.why(), Math.abs(ch.originY() - py) > capped);
        assertTrue(ch.why(), ch.why().contains(":AIR"));
        assertTrue(ch.why(), ch.why().contains("depths="));
        ConnectedPlan.Room from = c.room(ch.gx() - ConnectedPlan.dx(ch.parentDir()) * ch.span(),
                ch.gz() - ConnectedPlan.dz(ch.parentDir()) * ch.span());
        ConnectedPlan.Segment s = ConnectedPlan.join(c, c.air(), from, ch.parentDir(), ch.originY(), ch.span());
        assertTrue(ch.why(), ConnectedPlan.buildable(s, banded));
        assertTrue("room should sit past the cave band", Math.abs(s.room.originY() - py) > band);
    }
}
