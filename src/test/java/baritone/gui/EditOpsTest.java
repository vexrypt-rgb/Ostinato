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

package baritone.gui;

import baritone.gui.studio.EditOps;
import baritone.gui.studio.EditSession;
import baritone.gui.studio.StudioPick;
import baritone.gui.studio.StudioVolume;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** Box tools shared by the studio and the freecam canvas. None of this boots a client. */
public class EditOpsTest {

    @BeforeClass
    public static void boot() {
        // Load the block registry first; a test that touches it before Bootstrap leaves it broken for the whole JVM.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }


    @Test
    public void fillReplaceWallAndLineWriteOnlyTheCellsTheyClaim() {
        StudioVolume vol = new StudioVolume(6, 4, 6);
        assertEquals(8, EditOps.fill(vol, EditOps.Box.of(0, 0, 0, 1, 1, 1), 4));
        assertEquals(4, vol.get(1, 1, 1));
        assertEquals(0, vol.get(2, 0, 0));

        vol.set(0, 0, 0, 1);
        vol.set(1, 0, 0, 2);
        vol.set(2, 0, 0, 1);
        assertEquals(2, EditOps.replace(vol, EditOps.Box.of(0, 0, 0, 2, 0, 0), 1, 9));
        assertEquals(9, vol.get(0, 0, 0));
        assertEquals(2, vol.get(1, 0, 0));
        assertEquals(9, vol.get(2, 0, 0));

        StudioVolume shell = new StudioVolume(5, 5, 5);
        assertEquals(26, EditOps.wall(shell, EditOps.Box.of(0, 0, 0, 2, 2, 2), 3));
        assertEquals(3, shell.get(0, 0, 0));
        assertEquals(0, shell.get(1, 1, 1));

        StudioVolume line = new StudioVolume(6, 2, 2);
        assertEquals(4, EditOps.line(line, 0, 0, 0, 3, 0, 0, 4));
        for (int x = 0; x < 4; x++) {
            assertEquals(4, line.get(x, 0, 0));
        }
        assertEquals(0, line.get(4, 0, 0));
    }

    @Test
    public void sphereIncludesTheShellAndARadiusOneHollowIsSolid() {
        StudioVolume hollow = new StudioVolume(8, 8, 8);
        assertTrue(EditOps.sphere(hollow, 4, 4, 4, 2, 9, true) > 0);
        assertEquals(0, hollow.get(4, 4, 4));
        assertEquals(9, hollow.get(5, 4, 4));
        assertEquals(9, hollow.get(6, 4, 4));
        assertEquals(0, hollow.get(6, 5, 4));

        StudioVolume solid = new StudioVolume(5, 5, 5);
        StudioVolume shell = new StudioVolume(5, 5, 5);
        int solidCount = EditOps.sphere(solid, 2, 2, 2, 1, 3, false);
        int hollowCount = EditOps.sphere(shell, 2, 2, 2, 1, 3, true);
        assertEquals(solidCount, hollowCount);
        assertEquals(3, shell.get(2, 2, 2));
        assertArrayEquals(solid.snapshot(), shell.snapshot());
    }

    @Test
    public void copyPasteKeepsAirAndFourRotationsAreIdentity() {
        StudioVolume src = new StudioVolume(4, 2, 2);
        src.set(0, 0, 0, 1);
        src.set(2, 0, 0, 2);
        EditOps.Clipboard clip = EditOps.copy(src, EditOps.Box.of(0, 0, 0, 2, 0, 0));
        StudioVolume dest = new StudioVolume(6, 2, 2);
        dest.set(4, 0, 0, 7);
        EditOps.paste(dest, 3, 0, 0, clip);
        assertEquals(1, dest.get(3, 0, 0));
        assertEquals(0, dest.get(4, 0, 0));
        assertEquals(2, dest.get(5, 0, 0));

        EditOps.Clipboard turned = clip;
        for (int i = 0; i < 4; i++) {
            turned = turned.rotateY();
        }
        assertEquals(clip.sx, turned.sx);
        assertEquals(clip.sy, turned.sy);
        assertEquals(clip.sz, turned.sz);
        assertArrayEquals(clip.cells, turned.cells);

        StudioVolume square = new StudioVolume(8, 3, 8);
        square.set(1, 0, 1, 5);
        square.set(2, 0, 1, 6);
        square.set(2, 1, 1, 7);
        int[] start = square.snapshot();
        EditOps.Box box = EditOps.Box.of(1, 0, 1, 2, 1, 2);
        for (int i = 0; i < 4; i++) {
            assertTrue(EditOps.rotateSelection(square, box) >= 0);
        }
        assertArrayEquals(start, square.snapshot());

        EditOps.Clipboard flipped = clip.flipX().flipX();
        assertArrayEquals(clip.cells, flipped.cells);
    }

    @Test
    public void rotateThatDoesNotFitLeavesTheVolumeAlone() {
        StudioVolume vol = new StudioVolume(4, 2, 4);
        vol.set(2, 0, 0, 5);
        vol.set(2, 0, 1, 6);
        vol.set(2, 0, 2, 7);
        int[] before = vol.snapshot();
        assertEquals(-1, EditOps.rotateSelection(vol, EditOps.Box.of(2, 0, 0, 2, 0, 2)));
        assertArrayEquals(before, vol.snapshot());

        EditSession session = new EditSession();
        session.bind(vol);
        session.tool = EditOps.Tool.ROTATE;
        session.hasA = true;
        session.hasB = true;
        session.ax = 2;
        session.ay = 0;
        session.az = 0;
        session.bx = 2;
        session.by = 0;
        session.bz = 2;
        assertFalse(session.click(StudioPick.miss(), 0));
        assertEquals("That does not fit", session.note);
        assertArrayEquals(before, vol.snapshot());
        assertFalse(session.undo());
    }

    @Test
    public void selectionFillUsesTheTracedCornersAndUndoRestoresThem() {
        StudioVolume vol = new StudioVolume(8, 4, 8);
        vol.set(1, 0, 1, 1);
        vol.set(3, 0, 1, 1);
        EditSession session = new EditSession();
        session.bind(vol);
        session.brush = 4;
        session.tool = EditOps.Tool.SELECT;
        StudioPick a = StudioPick.trace(1.5, 2, 1.5, 0, -1, 0, vol);
        StudioPick b = StudioPick.trace(3.5, 2, 1.5, 0, -1, 0, vol);
        assertTrue(a.solid);
        assertTrue(b.solid);
        session.click(a, 0);
        session.click(b, 1);
        session.tool = EditOps.Tool.FILL;
        assertTrue(session.click(StudioPick.miss(), 0));
        assertEquals(4, vol.get(1, 0, 1));
        assertEquals(4, vol.get(2, 0, 1));
        assertEquals(4, vol.get(3, 0, 1));
        assertEquals(0, vol.get(2, 0, 0));
        assertTrue(session.undo());
        assertEquals(1, vol.get(1, 0, 1));
        assertEquals(0, vol.get(2, 0, 1));
        assertEquals(1, vol.get(3, 0, 1));
        assertEquals("Undone", session.note);
    }

    @Test
    public void quietBrushDoesNotInventANote() {
        StudioVolume vol = new StudioVolume(4, 2, 4);
        EditSession session = new EditSession();
        session.bind(vol);
        session.brush = 2;
        StudioPick floor = StudioPick.trace(1.2, 3, 1.2, 0, -1, 0, vol);
        assertTrue(floor.canPlace);
        assertTrue(session.click(floor, 0));
        assertNull(session.note);
        assertEquals(2, vol.get(floor.placeX, floor.placeY, floor.placeZ));
    }
}
