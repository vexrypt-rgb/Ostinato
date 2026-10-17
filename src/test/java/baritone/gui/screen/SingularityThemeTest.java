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


package baritone.gui.screen;

import baritone.gui.render.SingularityArtProbe;
import baritone.gui.render.Skin;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SingularityThemeTest {

    @Test
    public void themeNameIsForgiving() {
        assertTrue(Skin.isSingularity("singularity"));
        assertTrue(Skin.isSingularity(" Singularity "));
        assertFalse(Skin.isSingularity("classic"));
        assertFalse(Skin.isSingularity(""));
        assertFalse(Skin.isSingularity(null));
    }

    @Test
    public void hotRampRunsWhiteToEmber() {
        int[] c = Skin.hotColors(8);
        assertEquals(8, c.length);
        assertEquals(0xFFFFF8EC, c[0]);
        assertEquals(Skin.EMBER, c[7]);
        assertEquals(1, Skin.hotColors(1).length);
    }

    @Test
    public void holeIsBlackInsideAndClearFarOutside() {
        assertEquals(0xFF000000, SingularityArtProbe.pixel(0, -0.5)); // shadow, above the disk
        assertEquals(0, SingularityArtProbe.pixel(3.39, -3.39) >>> 24); // the corner fades to nothing
        // the photon ring is bright
        int ring = SingularityArtProbe.pixel(0, -1.035);
        assertTrue(Integer.toHexString(ring), (ring >>> 24) > 0xC0);
    }

    @Test
    public void approachingSideOfTheDiskIsBrighter() {
        int left = SingularityArtProbe.pixel(-2.0, 0.05), right = SingularityArtProbe.pixel(2.0, 0.05);
        assertTrue(lum(left) > lum(right));
    }

    @Test
    public void orbitSlotsStayOutsideTheDiskAndOnScreen() {
        List<Orbit.Body> bodies = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            bodies.add(new Orbit.Body("b" + i, i, null, false, false, i == 3 || i == 4));
        }
        for (int h : new int[]{240, 300, 480}) {
            Orbit o = new Orbit();
            o.layout(bodies, 854, h);
            assertTrue(o.ro >= o.rs * 3.4f + 16 - 1e-3);
            float prev = -1;
            for (int i = 0; i < bodies.size(); i++) {
                float y = slotY(o, i, h);
                assertTrue("slot " + i + " at h=" + h, y > 0 && y < h && y > prev); // on screen, top to bottom
                prev = y;
            }
            assertTrue(o.panelX0() < 854 - 300);
        }
        // GUI scale 4 at 1080p: the panel keeps the classic menu's narrowest width
        Orbit o = new Orbit();
        o.layout(bodies, 480, 270);
        assertFalse(o.labels);
        assertTrue(o.panelX0() <= 480 - 10 - 340 + 1e-3);
        assertTrue(o.cx < 30);
    }

    /** Finds slot i by scanning down the arc's right-hand edge. */
    private static float slotY(Orbit o, int i, int h) {
        for (float y = 0; y < h; y += 0.25f) {
            for (float x = o.cx - 12; x < o.cx + o.ro + 12; x += 1) {
                if (o.slotAt(x, y) == i) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static int lum(int argb) {
        int a = argb >>> 24;
        return a * (((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF));
    }
}
