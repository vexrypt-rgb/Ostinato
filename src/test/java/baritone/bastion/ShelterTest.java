package baritone.bastion;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ShelterTest {

    /** Full blocks or nothing; cells in "taken" refuse a block. */
    private static final class World implements Shelter.Grid {
        final Set<String> blocks = new HashSet<>(), taken = new HashSet<>();

        static String k(int x, int y, int z) { return x + "," + y + "," + z; }

        World floor(int y, int r) {
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) blocks.add(k(x, y, z));
            return this;
        }

        World set(int x, int y, int z) { blocks.add(k(x, y, z)); return this; }

        World clear(int x, int y, int z) { blocks.remove(k(x, y, z)); return this; }

        public boolean open(int x, int y, int z) { return !blocks.contains(k(x, y, z)); }

        public boolean solid(int x, int y, int z) { return blocks.contains(k(x, y, z)); }

        public boolean blocked(int x, int y, int z) { return taken.contains(k(x, y, z)); }
    }

    /** Places until there is nothing left to place, checking every click is one the game would take. */
    private static List<Shelter.Place> build(World w, double feetY, double tx, double tz) {
        return build(w, feetY, tx, tz, false);
    }

    private static List<Shelter.Place> build(World w, double feetY, double tx, double tz, boolean ring) {
        List<Shelter.Place> out = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Shelter.Place p = ring ? Shelter.fort(w, 0, feetY, 0, tx, tz) : Shelter.next(w, 0, feetY, 0, tx, tz);
            if (p == null) return out;
            assertTrue("cell is free", w.open(p.x(), p.y(), p.z()) && !w.blocked(p.x(), p.y(), p.z()));
            assertTrue("clicked block is there", w.solid(p.ax(), p.ay(), p.az()));
            assertEquals("the face clicked looks into the cell", World.k(p.x(), p.y(), p.z()), World.k(p.ax() + p.fx(), p.ay() + p.fy(), p.az() + p.fz()));
            assertFalse("never our own cells", p.x() == 0 && p.z() == 0 && p.y() >= Shelter.low(feetY) && p.y() <= Shelter.high(feetY));
            w.set(p.x(), p.y(), p.z());
            out.add(p);
        }
        fail("never finished");
        return out;
    }

    @Test
    public void inTheOpenTenBlocksShutUsIn() {
        World w = new World().floor(63, 4);
        List<Shelter.Place> placed = build(w, 64, 5, 0);
        assertTrue(Shelter.walled(w, 0, 64, 0));
        assertTrue(Shelter.roofed(w, 0, 64, 0));
        assertEquals(10, placed.size());
    }

    @Test
    public void theSideFacingTheBruteClosesFirst() {
        World w = new World().floor(63, 4);
        List<Shelter.Place> placed = build(w, 64, -3, 9);
        assertEquals("0,64,1", World.k(placed.get(0).x(), placed.get(0).y(), placed.get(0).z()));
        assertEquals("0,65,1", World.k(placed.get(1).x(), placed.get(1).y(), placed.get(1).z()));
        assertEquals("-1,64,0", World.k(placed.get(2).x(), placed.get(2).y(), placed.get(2).z()));
    }

    @Test
    public void theRoofIsNeverAFallingBlocksJobAndComesLast() {
        World w = new World().floor(63, 4);
        List<Shelter.Place> placed = build(w, 64, 5, 0);
        Shelter.Place last = placed.get(placed.size() - 1), prop = placed.get(placed.size() - 2);
        assertTrue(last.roof());
        assertEquals("0,66,0", World.k(last.x(), last.y(), last.z()));
        // the block the roof hangs from stands on a wall, away from the brute
        assertEquals("-1,66,0", World.k(prop.x(), prop.y(), prop.z()));
        assertEquals("-1,65,0", World.k(prop.ax(), prop.ay(), prop.az()));
        for (Shelter.Place p : placed.subList(0, 8)) assertFalse(p.roof());
    }

    @Test
    public void underALowCeilingTheWallsAreEnough() {
        World w = new World().floor(63, 4).floor(66, 4);
        List<Shelter.Place> placed = build(w, 64, 0, -6);
        assertTrue(Shelter.walled(w, 0, 64, 0));
        assertEquals(8, placed.size());
    }

    @Test
    public void inACorridorOnlyTheOpenEndsAreFilled() {
        World w = new World().floor(63, 4).floor(66, 4);
        for (int x = -4; x <= 4; x++) for (int y = 64; y <= 65; y++) w.set(x, y, 1).set(x, y, -1);
        List<Shelter.Place> placed = build(w, 64, 6, 0);
        assertTrue(Shelter.walled(w, 0, 64, 0));
        assertEquals(4, placed.size());
    }

    @Test
    public void aStepDownIsBuiltUpFromItsFloor() {
        // the ground to the east is one lower: a brute down there still reaches our legs
        World w = new World().floor(63, 4).floor(62, 4);
        for (int x = 1; x <= 4; x++) for (int z = -4; z <= 4; z++) w.clear(x, 63, z);
        List<Shelter.Place> placed = build(w, 64, 5, 0);
        assertTrue(Shelter.walled(w, 0, 64, 0));
        assertEquals("1,63,0", World.k(placed.get(0).x(), placed.get(0).y(), placed.get(0).z()));
        assertEquals(11, placed.size());
    }

    @Test
    public void overADeepDropTheWallHangsFromOurOwnFloor() {
        World w = new World().floor(63, 4);
        for (int x = 1; x <= 4; x++) for (int z = -4; z <= 4; z++) w.clear(x, 63, z);
        List<Shelter.Place> placed = build(w, 64, -5, 0);
        assertTrue(Shelter.walled(w, 0, 64, 0));
        assertTrue(Shelter.roofed(w, 0, 64, 0));
        // one block against the side of the floor block we stand on, then the wall on top of it
        Shelter.Place hung = placed.stream().filter(p -> p.x() == 1 && p.y() == 63).findFirst().get();
        assertEquals("0,63,0", World.k(hung.ax(), hung.ay(), hung.az()));
        assertEquals(11, placed.size());
    }

    @Test
    public void onASlabTheWallsGoOneHigher() {
        // feet at 64.5: the head is in the third level of cells
        World w = new World().floor(63, 4).set(0, 64, 0);
        List<Shelter.Place> placed = build(w, 64.5, 5, 0);
        assertTrue(Shelter.walled(w, 0, 64.5, 0));
        assertTrue(Shelter.roofed(w, 0, 64.5, 0));
        assertEquals(3, Shelter.high(64.5) - Shelter.low(64.5) + 1);
        assertEquals(14, placed.size());
    }

    @Test
    public void theRingShutsTheCornersAndTopsTheWalls() {
        World w = new World().floor(63, 4);
        build(w, 64, 5, 0);
        assertFalse(Shelter.fortified(w, 0, 64, 0));
        List<Shelter.Place> ring = build(w, 64, 5, 0, true);
        assertTrue(Shelter.fortified(w, 0, 64, 0));
        // eight corner blocks, and three wall tops: the fourth already holds the roof
        assertEquals(11, ring.size());
        // the corners toward the brute first, from the floor up
        assertEquals(1, ring.get(0).x());
        assertEquals(64, ring.get(0).y());
        assertEquals(1, ring.get(1).x());
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 64; y <= 65; y++) assertEquals(x == 0 && z == 0, w.open(x, y, z));
    }

    @Test
    public void theRingOnASlabIsAsHighAsTheWalls() {
        World w = new World().floor(63, 4).set(0, 64, 0);
        build(w, 64.5, 5, 0);
        List<Shelter.Place> ring = build(w, 64.5, 5, 0, true);
        assertTrue(Shelter.fortified(w, 0, 64.5, 0));
        assertEquals(15, ring.size());
    }

    @Test
    public void aCornerOverADropHangsFromTheWalls() {
        World w = new World().floor(63, 4);
        for (int x = 1; x <= 4; x++) for (int z = -4; z <= 4; z++) w.clear(x, 63, z);
        build(w, 64, -5, 0);
        List<Shelter.Place> ring = build(w, 64, -5, 0, true);
        assertTrue(Shelter.fortified(w, 0, 64, 0));
        Shelter.Place hung = ring.stream().filter(p -> p.x() == 1 && p.y() == 64 && p.z() == 1).findFirst().get();
        assertEquals(64, hung.ay());
        assertEquals(11, ring.size());
    }

    @Test
    public void theSlitIsAtEyeLevelInTheWallFacingTheBrute() {
        assertArrayEquals(new int[]{1, 65, 0}, Shelter.slit(0, 65, 0, 5, 1));
        assertArrayEquals(new int[]{0, 65, -1}, Shelter.slit(0, 65, 0, -1, -7));
        assertEquals(65, Shelter.eye(64));
        // a brute on our own floor: eye to eye
        assertEquals(65, Shelter.slitLevel(64, 64 + 1.79));
        // on a slab our standing eye is a level over its eye; sneaking, it is level with it
        assertEquals(66, Shelter.eye(64.5));
        assertEquals(65, Shelter.slitLevel(64.5, 64 + 1.79));
        // one on a floor a block lower, or two higher: neither stance meets its eye, so we stand
        assertEquals(65, Shelter.slitLevel(64, 63 + 1.79));
        assertEquals(65, Shelter.slitLevel(64, 66 + 1.79));
    }

    @Test
    public void aCellSomebodyStandsInIsPassedOver() {
        World w = new World().floor(63, 4);
        w.taken.add(World.k(1, 64, 0));
        List<Shelter.Place> placed = build(w, 64, 5, 0);
        assertFalse(Shelter.walled(w, 0, 64, 0));
        // the other three sides and the roof are closed; the cell it stands in is left, and the one over it has nothing to hold it
        assertTrue(w.open(1, 64, 0));
        assertTrue(w.open(1, 65, 0));
        assertEquals(8, placed.size());
        w.taken.clear();
        assertNotNull(Shelter.next(w, 0, 64, 0, 5, 0));
    }
}
