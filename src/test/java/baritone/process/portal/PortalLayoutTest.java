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
import baritone.process.portal.PortalLayout.Face;
import baritone.process.portal.PortalLayout.Placement;
import baritone.process.portal.PortalLayout.Site;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PortalLayoutTest {

    @Test
    public void frameSurroundsATwoByThreeInterior() {
        Set<Cell> interior = new HashSet<>(PortalLayout.INTERIOR);
        Set<Cell> frame = new HashSet<>(PortalLayout.FRAME);
        assertEquals(6, interior.size());
        assertEquals(10, frame.size());
        Face[] inPlane = {Face.UP, Face.DOWN, Face.PLUS_X, Face.MINUS_X};
        for (Cell c : interior) {
            assertEquals("the portal stands in the lava row", 0, c.z);
            for (Face f : inPlane) {
                Cell n = c.offset(f);
                assertTrue(n + " beside " + c, interior.contains(n) || frame.contains(n));
            }
        }
        for (Cell c : frame) {
            assertFalse(interior.contains(c));
        }
    }

    @Test
    public void frameIsTheFloorPlusTheCasts() {
        Set<Cell> made = new HashSet<>(PortalLayout.FLOOR);
        for (Placement p : PortalLayout.CASTS) {
            assertTrue("cast twice: " + p, made.add(p.cell));
        }
        assertEquals(new HashSet<>(PortalLayout.FRAME), made);
    }

    @Test
    public void everyClickHasItsSupportByThen() {
        // what is solid once the first water has done its work
        Set<Cell> solid = new HashSet<>(PortalLayout.RIM);
        check(PortalLayout.BASE_BLOCK, solid);
        check(PortalLayout.BASE_WATER, solid);
        solid.remove(PortalLayout.BASE_BLOCK.cell);
        solid.remove(PortalLayout.BASE_WATER.cell);
        solid.addAll(PortalLayout.FLOOR);
        solid.add(PortalLayout.STAND_A);
        solid.add(PortalLayout.STAND_B);
        for (Placement p : PortalLayout.SHAPES) {
            check(p, solid);
        }
        check(PortalLayout.CORNER, solid);
        check(PortalLayout.MOLD_WATER, solid);
        solid.remove(PortalLayout.MOLD_WATER.cell);
        for (Placement p : PortalLayout.CASTS) {
            check(p, solid);
        }
    }

    private static void check(Placement p, Set<Cell> solid) {
        assertTrue(p + " has nothing to be clicked against", solid.contains(p.support));
        assertFalse(p + " is already there", solid.contains(p.cell));
        solid.add(p.cell);
    }

    @Test
    public void castOrderKeepsTheWaterWhereItIsNeeded() {
        List<String> order = new ArrayList<>();
        for (Placement p : PortalLayout.CASTS) {
            order.add(p.name);
        }
        // the first top cell cuts off the column of water the left side sets against
        assertTrue(order.indexOf("left 3") < order.indexOf("top 1"));
        assertTrue(order.indexOf("top 1") < order.indexOf("top 2"));
        assertTrue(order.indexOf("right 2") < order.indexOf("right 3"));
        // the water goes on the pillar's top block and next to both top cells
        Cell water = PortalLayout.MOLD_WATER.cell;
        assertEquals(water, new Cell(1, 4, -1));
        assertEquals(water.offset(Face.PLUS_Z), PortalLayout.CASTS.get(3).cell);
    }

    @Test
    public void clearCellsCoverEverythingPlacedAndThePortal() {
        Set<Cell> clear = new HashSet<>(PortalLayout.CLEAR);
        for (Placement p : PortalLayout.SHAPES) {
            assertTrue(p.toString(), clear.contains(p.cell));
        }
        assertTrue(clear.contains(PortalLayout.CORNER.cell));
        assertTrue(clear.contains(PortalLayout.MOLD_WATER.cell));
        assertTrue(clear.contains(PortalLayout.RIM_FEET));
        for (Cell c : PortalLayout.INTERIOR) {
            assertTrue(c.toString(), c.y == 1 || clear.contains(c));
        }
        for (Placement p : PortalLayout.CASTS) {
            assertTrue(p.toString(), p.cell.y == 1 || clear.contains(p.cell));
        }
        for (Cell c : clear) {
            assertTrue("nothing is cleared in the lava layer", c.y >= 2);
        }
    }

    @Test
    public void eightOrientationsRoundTrip() {
        List<Site> sites = Site.orientations(10, 60, -20);
        assertEquals(8, sites.size());
        Set<String> seen = new HashSet<>();
        int mirrored = 0;
        for (Site s : sites) {
            assertTrue(seen.add(s.outX + "," + s.outZ + "," + s.rightX + "," + s.rightZ));
            assertEquals("right is across out", 0, s.outX * s.rightX + s.outZ * s.rightZ);
            // facing out, an unmirrored site has x growing to the right hand
            int hand = s.outX * s.rightZ - s.outZ * s.rightX;
            assertEquals(s.isMirrored() ? -1 : 1, hand);
            mirrored += s.isMirrored() ? 1 : 0;
            for (Cell c : PortalLayout.FRAME) {
                assertEquals(c, s.cellAt(s.worldX(c), s.worldY(c), s.worldZ(c)));
                assertEquals(s.worldX(c) + 0.5, s.pointX(c.x + 0.5, c.z + 0.5), 1e-9);
                assertEquals(s.worldZ(c) + 0.5, s.pointZ(c.x + 0.5, c.z + 0.5), 1e-9);
            }
            for (Face f : Face.values()) {
                Cell c = new Cell(1, 2, 0);
                Cell n = c.offset(f);
                int[] step = s.worldStep(f);
                assertEquals(s.worldX(n), s.worldX(c) + step[0]);
                assertEquals(s.worldY(n), s.worldY(c) + step[1]);
                assertEquals(s.worldZ(n), s.worldZ(c) + step[2]);
            }
        }
        assertEquals(4, mirrored);
    }

    @Test
    public void facingSouthXGrowsWest() {
        Site s = new Site(0, 0, 0, 0, 1, false);
        assertEquals(-1, s.worldX(1, 0));
        assertEquals(0, s.worldZ(1, 0));
        assertEquals(1, s.worldZ(0, 1));
        Site m = new Site(0, 0, 0, 0, 1, true);
        assertEquals(1, m.worldX(1, 0));
        assertNotEquals(s.toString(), m.toString());
    }

    @Test
    public void anchoredSitePutsTheAnchorThere() {
        Cell anchor = PortalLayout.PIT.get(0);
        for (Site o : Site.orientations(0, 0, 0)) {
            Site s = o.anchoredAt(anchor, 100, 62, -7);
            assertEquals(100, s.worldX(anchor));
            assertEquals(62, s.worldY(anchor));
            assertEquals(-7, s.worldZ(anchor));
            assertEquals(o.isMirrored(), s.isMirrored());
        }
    }
}
