package baritone.bastion;

import org.junit.Test;

import java.util.List;

import static baritone.bastion.BruteTactics.*;
import static org.junit.Assert.*;

public class BruteTacticsTest {
    /** Flat floor at y=63 (open air above), optional wall. */
    static LavaFlow.Grid flat(int wallX) {
        return new LavaFlow.Grid() {
            public int lava(int x, int y, int z) { return -1; }
            public boolean open(int x, int y, int z) { return y >= 64 && x != wallX; }
        };
    }

    @Test
    public void choosePlan() {
        assertEquals(Plan.NONE, choose("lava", 20, false, true, true, true));
        assertEquals(Plan.LAVA, choose("lava", 6, false, true, true, true));
        assertEquals(Plan.PIT, choose("lava", 6, false, false, false, true));
        assertEquals(Plan.PIT, choose("pit", 6, false, true, true, true));
        assertEquals(Plan.LAVA, choose("lava", 20, true, true, true, false));
        assertEquals(Plan.NONE, choose("off", 3, true, true, true, true));
        assertEquals(Plan.NONE, choose("lava-only", 6, false, false, true, true));
    }

    @Test
    public void pourNeverReachesUs() {
        // open floor: lava runs 7 blocks, so a brute 5 away is not safe to pour on
        assertFalse(pourSafe(flat(Integer.MIN_VALUE), 5, 64, 0, 0, 64, 0));
        // a wall at x=2 between us: safe
        assertTrue(pourSafe(flat(2), 5, 64, 0, 0, 64, 0));
        // far enough on open floor
        assertTrue(pourSafe(flat(Integer.MIN_VALUE), 10, 64, 0, 0, 64, 0));
        // too close regardless
        assertFalse(pourSafe(flat(1), 2, 64, 0, 0, 64, 0));
    }

    @Test
    public void candidatesAndScoop() {
        List<int[]> c = pourCandidates(5, 64, 1, 0, 0);
        assertArrayEquals(new int[]{5, 64, 1}, c.get(0));
        assertArrayEquals(new int[]{4, 64, 1}, c.get(1));
        assertTrue(scoopNow(10, false, true, false));
        assertTrue(scoopNow(BURN_TICKS, true, true, false));
        assertFalse(scoopNow(20, true, true, false));
        assertTrue(scoopNow(50, true, false, false));
        assertTrue(scoopNow(5, true, true, true));
    }

    @Test
    public void pitSharesAWallNotOurSide() {
        // trade hole at (0,64,0), we stand at (-1,64,0): the pit is never at (-1..) next to us
        List<int[]> s = pitSites(0, 64, 0, -2, 0);
        assertEquals(3, s.size());
        for (int[] p : s) assertTrue(Math.abs(p[0] + 2) + Math.abs(p[2]) > 1);
        assertTrue(s.stream().anyMatch(p -> p[0] == 1 && p[2] == 0));
        assertEquals(2, pitSites(0, 64, 0, -1, 1).size());
    }

    @Test
    public void lureAndTrap() {
        assertArrayEquals(new int[]{4, 64, 0}, lureStand(3, 64, 0, 0.5, 0.5));
        assertArrayEquals(new int[]{3, 64, -1}, lureStand(3, 64, 0, 3.5, 6.0));
        assertTrue(inPit(3, 64, 0, 3, 62.0, 0));
        assertFalse(inPit(3, 64, 0, 3, 64.0, 0));
        assertFalse(inPit(3, 64, 0, 4, 62.0, 0));
    }
}
