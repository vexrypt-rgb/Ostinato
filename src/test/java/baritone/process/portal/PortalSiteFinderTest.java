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

package baritone.process.portal;

import baritone.process.portal.PortalLayout.Cell;
import baritone.process.portal.PortalLayout.Site;
import baritone.process.portal.PortalSiteFinder.Candidate;
import baritone.process.portal.PortalSiteFinder.Kind;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalSiteFinderTest {

    /** Flat stone up to and including {@link #GROUND}, air above, and whatever is set on top of that. */
    private static final class World implements PortalSiteFinder.Cells {

        static final int GROUND = 63;
        final Map<Long, Kind> set = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) (x + 1024) << 40) | ((long) (y + 1024) << 20) | (z + 1024);
        }

        void set(int x, int y, int z, Kind k) {
            set.put(key(x, y, z), k);
        }

        @Override
        public Kind at(int x, int y, int z) {
            Kind k = set.get(key(x, y, z));
            return k != null ? k : y <= GROUND ? Kind.SOLID : Kind.AIR;
        }

        /** Lava from the surface down, over a rectangle. */
        World pool(int x0, int z0, int x1, int z1, int depth) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int d = 0; d < depth; d++) {
                        set(x, GROUND - d, z, Kind.LAVA_SOURCE);
                    }
                }
            }
            return this;
        }

        void set(Site s, Cell c, Kind k) {
            set(s.worldX(c), s.worldY(c), s.worldZ(c), k);
        }
    }

    /** A pool 8 by 6 whose north shore is the row z = -1. */
    private static World bigPool(int depth) {
        return new World().pool(0, 0, 7, 5, depth);
    }

    /** The unmirrored site on the north shore of {@link #bigPool} with its row on x = 5..2. */
    private static Site northShore() {
        // facing south, x grows west: cell (0,1,0) at world x = 5
        return new Site(5, World.GROUND - 1, 0, 0, 1, false);
    }

    @Test
    public void acceptsADeepPool() {
        World w = bigPool(2);
        Candidate c = PortalSiteFinder.evaluate(w, northShore(), false);
        assertTrue(String.valueOf(c.reject), c.usable());
        assertFalse(c.oneDeep());
        assertFalse(c.needsPickaxe());
        assertTrue(c.breaks.isEmpty());
        assertTrue(c.rimPlaces.isEmpty());
        assertTrue(c.lava >= PortalLayout.LAVA_NEEDED);
        assertEquals(PortalLayout.BLOCKS_NEEDED, c.blocksNeeded());
    }

    @Test
    public void flagsAPoolOneDeepAndWantsAPickaxe() {
        World w = bigPool(1);
        Candidate without = PortalSiteFinder.evaluate(w, northShore(), false);
        assertFalse(without.usable());
        Candidate with = PortalSiteFinder.evaluate(w, northShore(), true);
        assertTrue(String.valueOf(with.reject), with.usable());
        assertTrue(with.oneDeep());
        assertEquals(PortalLayout.FLOOR, with.shallowFloor);
        assertEquals(PortalLayout.LAVA_NEEDED + 2, with.lavaNeeded());
    }

    @Test
    public void rejectsAPoolTooSmallToPourFrom() {
        // the row and one more: nothing left to scoop once the base has set
        World w = new World().pool(2, 0, 5, 1, 2);
        Candidate c = PortalSiteFinder.evaluate(w, northShore(), true);
        assertFalse(c.usable());
        assertTrue(c.reject, c.reject.contains("lava sources in reach"));
    }

    @Test
    public void rejectsARowThatIsNotLava() {
        World w = bigPool(2);
        Site s = northShore();
        w.set(s, PortalLayout.PIT.get(1), Kind.SOLID);
        assertFalse(PortalSiteFinder.evaluate(w, s, true).usable());
        w.set(s, PortalLayout.PIT.get(1), Kind.LAVA_FLOW);
        assertFalse(PortalSiteFinder.evaluate(w, s, true).usable());
    }

    @Test
    public void stoneAtARowEndIsDugAndCast() {
        World w = bigPool(2);
        Site s = northShore();
        w.set(s, new Cell(0, 1, 0), Kind.SOLID);
        assertFalse(PortalSiteFinder.evaluate(w, s, false).usable());
        Candidate c = PortalSiteFinder.evaluate(w, s, true);
        assertTrue(String.valueOf(c.reject), c.usable());
        assertEquals(1, c.solidEnds.size());
        assertTrue(c.needsPickaxe());
        assertFalse(c.oneDeep());
    }

    @Test
    public void listsWhatIsInTheWay() {
        World w = bigPool(2);
        Site s = northShore();
        w.set(s, new Cell(1, 2, -1), Kind.PLANT);
        w.set(s, new Cell(0, 3, -1), Kind.SOLID);
        Candidate c = PortalSiteFinder.evaluate(w, s, false);
        assertTrue(String.valueOf(c.reject), c.usable());
        assertEquals(2, c.breaks.size());
        assertEquals(1, c.solidBreaks);
        assertTrue(c.cost > PortalSiteFinder.evaluate(bigPool(2), s, false).cost);

        // a chest, or water, is not something to clear
        w.set(s, new Cell(2, 2, 0), Kind.OTHER);
        assertFalse(PortalSiteFinder.evaluate(w, s, false).usable());
        w.set(s, new Cell(2, 2, 0), Kind.WATER);
        assertFalse(PortalSiteFinder.evaluate(w, s, false).usable());
    }

    @Test
    public void tooMuchInTheWayIsRejected() {
        World w = bigPool(2);
        Site s = northShore();
        for (int i = 0; i <= PortalSiteFinder.MAX_SOLID_BREAKS; i++) {
            w.set(s, PortalLayout.CLEAR.get(i), Kind.SOLID);
        }
        assertFalse(PortalSiteFinder.evaluate(w, s, true).usable());
    }

    @Test
    public void aMissingRimEndIsFilledInButNotAMiddleOne() {
        World w = bigPool(2);
        Site s = northShore();
        w.set(s, PortalLayout.RIM.get(3), Kind.LAVA_SOURCE);
        Candidate c = PortalSiteFinder.evaluate(w, s, false);
        assertTrue(String.valueOf(c.reject), c.usable());
        assertEquals(1, c.rimPlaces.size());
        assertEquals(PortalLayout.BLOCKS_NEEDED + 1, c.blocksNeeded());

        w.set(s, PortalLayout.RIM.get(1), Kind.LAVA_SOURCE);
        assertFalse(PortalSiteFinder.evaluate(w, s, false).usable());
    }

    @Test
    public void findsTheShoreFromEverySideAndBothHands() {
        World w = bigPool(2);
        List<Candidate> found = PortalSiteFinder.find(w, 3, World.GROUND + 1, -3, 16, 4, false);
        assertFalse(found.isEmpty());
        boolean[] out = new boolean[4];
        boolean mirrored = false, plain = false;
        for (Candidate c : found) {
            assertTrue(c.usable());
            Site s = c.site;
            out[s.outZ == 1 ? 0 : s.outZ == -1 ? 1 : s.outX == 1 ? 2 : 3] = true;
            mirrored |= s.isMirrored();
            plain |= !s.isMirrored();
            // the rim really is the shore
            for (Cell rim : PortalLayout.RIM) {
                assertEquals(Kind.SOLID, w.at(s.worldX(rim), s.worldY(rim), s.worldZ(rim)));
            }
            for (Cell pit : PortalLayout.PIT) {
                assertEquals(Kind.LAVA_SOURCE, w.at(s.worldX(pit), s.worldY(pit), s.worldZ(pit)));
            }
        }
        assertTrue(out[0] && out[1] && out[2] && out[3]);
        assertTrue(mirrored && plain);
        // standing north of the pool, the nearest site is on the north shore
        Candidate best = found.get(0);
        assertEquals(1, best.site.outZ);
        assertEquals(-1, best.site.worldZ(PortalLayout.RIM_FEET));
        for (int i = 1; i < found.size(); i++) {
            assertTrue(found.get(i - 1).cost <= found.get(i).cost);
        }
    }

    @Test
    public void aMirroredSiteFitsBesideABlockedColumn() {
        World w = bigPool(2);
        Site plain = northShore();
        // something that cannot be cleared stands on the rim at world x = 2, where this site's bar goes
        w.set(plain, new Cell(3, 2, -1), Kind.OTHER);
        assertFalse(PortalSiteFinder.evaluate(w, plain, true).usable());
        // the mirrored site on world x = 3..6 does not touch that column
        Site mirrored = new Site(3, plain.oy, plain.oz, 0, 1, true);
        assertEquals(3, mirrored.worldX(0, 0));
        assertEquals(6, mirrored.worldX(3, 0));
        Candidate c = PortalSiteFinder.evaluate(w, mirrored, true);
        assertTrue(String.valueOf(c.reject), c.usable());
    }

    @Test
    public void nothingFoundWithoutLava() {
        assertTrue(PortalSiteFinder.find(new World(), 0, World.GROUND + 1, 0, 16, 4, true).isEmpty());
    }
}
