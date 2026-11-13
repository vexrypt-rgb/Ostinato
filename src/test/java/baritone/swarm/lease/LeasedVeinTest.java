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

import org.junit.After;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c. A leased mine heads for the vein box its verified lease holds, mines only the leased ore in it
 * and ends once its own world shows the box mined out.
 */
public class LeasedVeinTest {

    private static final ReservationKey BOX = ReservationKey.vein('o', 10, 20, 30, 11, 21, 31);

    @After
    public void reset() {
        LeaseFence.clear();
    }

    private static LeasedVein.Blocks world(Set<String> ore, Set<String> unloaded) {
        return (x, y, z) -> {
            String p = x + "," + y + "," + z;
            return unloaded.contains(p) ? LeasedVein.Blocks.UNLOADED
                    : ore.contains(p) ? LeasedVein.Blocks.MATCH : LeasedVein.Blocks.OTHER;
        };
    }

    private static Set<String> set(String... ps) {
        Set<String> s = new HashSet<>();
        for (String p : ps) {
            s.add(p);
        }
        return s;
    }

    @Test
    public void oreInTheBoxIsTheTarget() {
        LeasedVein.Scan s = LeasedVein.scan(BOX, world(set("10,20,30", "11,21,31", "12,20,30"), set("10,21,30")));
        assertEquals(LeasedVein.State.ORE, s.state);
        assertEquals("only ore inside the box, an unloaded corner does not hide it", 2, s.ore.size());
        assertArrayEquals(new int[]{10, 20, 30}, s.ore.get(0));
        assertArrayEquals(new int[]{11, 21, 31}, s.ore.get(1));
    }

    @Test
    public void unloadedPartWithoutOreIsUnknownNotMinedOut() {
        LeasedVein.Scan s = LeasedVein.scan(BOX, world(set("12,20,30"), set("11,20,31")));
        assertEquals(LeasedVein.State.UNKNOWN, s.state);
        assertTrue(s.ore.isEmpty());
    }

    @Test
    public void loadedBoxWithoutOreIsMinedOut() {
        LeasedVein.Scan s = LeasedVein.scan(BOX, world(set("9,20,30", "12,21,31"), set("12,20,30")));
        assertEquals(LeasedVein.State.MINED_OUT, s.state);
        assertTrue(s.ore.isEmpty());
    }

    @Test
    public void hugeBoxIsNotWalked() {
        int[] asked = {0};
        // 32 x 32 x 33 = 33792 blocks, over the limit; 32 x 32 x 32 = 32768 is at it and is walked.
        ReservationKey big = ReservationKey.region('o', 0, 0, 0, 31, 31, 32);
        LeasedVein.Scan s = LeasedVein.scan(big, (x, y, z) -> {
            asked[0]++;
            return LeasedVein.Blocks.MATCH;
        });
        assertEquals(LeasedVein.State.UNKNOWN, s.state);
        assertEquals(0, asked[0]);
        ReservationKey edge = ReservationKey.region('o', 0, 0, 0, 31, 31, 31);
        LeasedVein.Scan e = LeasedVein.scan(edge, (x, y, z) -> {
            asked[0]++;
            return LeasedVein.Blocks.OTHER;
        });
        assertEquals(LeasedVein.State.MINED_OUT, e.state);
        assertEquals(LeasedVein.MAX_SCAN, asked[0]);
    }

    @Test
    public void keepDropsOnlyOreOutsideTheBox() {
        assertTrue("no vein held: everything stays", LeasedVein.keep(null, 99, 99, 99, true));
        assertTrue("ore inside", LeasedVein.keep(BOX, 11, 21, 31, true));
        assertFalse("ore outside", LeasedVein.keep(BOX, 12, 21, 31, true));
        assertFalse("ore below", LeasedVein.keep(BOX, 10, 19, 30, true));
        assertTrue("a dropped item outside stays a goal", LeasedVein.keep(BOX, 12, 21, 31, false));
    }

    @Test
    public void centreIsTheMiddleBlock() {
        assertArrayEquals(new int[]{10, 20, 30}, LeasedVein.centre(BOX));
        assertArrayEquals("rounds down below zero", new int[]{-3, 5, -2},
                LeasedVein.centre(ReservationKey.vein('o', -3, 4, -2, -2, 6, -1)));
    }

    @Test
    public void veinTargetFailsSafeAndClears() {
        assertNull("nothing installed", LeaseFence.veinTarget());
        LeaseFence.installVein(() -> BOX);
        assertSame(BOX, LeaseFence.veinTarget());
        LeaseFence.installVein(() -> {
            throw new IllegalStateException("guard gone");
        });
        assertNull("a broken supplier gives no target", LeaseFence.veinTarget());
        LeaseFence.installVein(() -> BOX);
        LeaseFence.clear();
        assertNull("clear drops the vein hook", LeaseFence.veinTarget());
        LeaseFence.installVein(null);
        assertNull(LeaseFence.veinTarget());
    }
}
