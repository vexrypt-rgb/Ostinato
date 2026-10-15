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

import baritone.gui.play.Minefield;
import baritone.gui.play.Motif;
import baritone.gui.play.Slide2048;
import baritone.gui.studio.BlockPalette;
import baritone.gui.studio.StudioCamera;
import baritone.gui.studio.StudioPick;
import baritone.gui.studio.StudioVolume;
import org.junit.Test;

import static org.junit.Assert.*;

/** Studio camera, picking, and the arcade rules. None of this boots a client. */
public class StudioLogicTest {

    @Test
    public void cameraLooksDownPlusZAndProjectsTheTargetToCenter() {
        StudioCamera cam = new StudioCamera();
        cam.yaw = 0;
        cam.pitch = 0;
        cam.distance = 10;
        cam.lookAt(0, 0, 0);
        double[] eye = new double[3];
        cam.eye(eye);
        assertEquals(0, eye[0], 1e-6);
        assertEquals(0, eye[1], 1e-6);
        assertEquals(10, eye[2], 1e-6);

        double[] out = new double[3];
        cam.project(0, 0, 0, 0, 0, 200, 100, out);
        assertEquals(100, out[0], 1e-4);
        assertEquals(50, out[1], 1e-4);
        assertEquals(10, out[2], 1e-4);

        cam.project(1, 0, 0, 0, 0, 200, 100, out);
        assertTrue(out[0] > 100);

        double[] origin = new double[3];
        double[] dir = new double[3];
        cam.ray(100, 50, 0, 0, 200, 100, origin, dir);
        assertEquals(0, dir[0], 1e-6);
        assertEquals(0, dir[1], 1e-6);
        assertTrue(dir[2] < 0);
    }

    @Test
    public void orbitClampsPitchAndDollyClampsDistance() {
        StudioCamera cam = new StudioCamera();
        cam.pitch = 0;
        cam.orbit(0, 10);
        assertEquals(1.25, cam.pitch, 1e-9);
        cam.orbit(0, -20);
        assertEquals(-1.25, cam.pitch, 1e-9);
        cam.distance = 28;
        cam.dolly(1);
        assertEquals(28 * 0.9, cam.distance, 1e-9);
        cam.dolly(100);
        assertEquals(6, cam.distance, 1e-9);
        cam.dolly(-100);
        assertEquals(90, cam.distance, 1e-9);
    }

    @Test
    public void pickHitsTheFaceNeighborAndTheFloor() {
        StudioVolume vol = new StudioVolume(16, 8, 16);
        vol.set(4, 0, 4, 1);
        StudioPick hit = StudioPick.trace(8, 0.5, 4.5, -1, 0, 0, vol);
        assertTrue(hit.solid);
        assertEquals(4, hit.x);
        assertEquals(0, hit.y);
        assertEquals(4, hit.z);
        assertTrue(hit.canPlace);
        assertEquals(5, hit.placeX);
        assertEquals(0, hit.placeY);
        assertEquals(4, hit.placeZ);

        StudioVolume empty = new StudioVolume(16, 8, 16);
        StudioPick floor = StudioPick.trace(3.2, 10, 3.2, 0, -1, 0, empty);
        assertFalse(floor.solid);
        assertTrue(floor.canPlace);
        assertEquals(3, floor.placeX);
        assertEquals(0, floor.placeY);
        assertEquals(3, floor.placeZ);
    }

    @Test
    public void volumeRoundTripsAndRejectsUnknownBlocks() {
        assertEquals("stone", BlockPalette.name(1));
        assertEquals(1, BlockPalette.cell("stone"));
        assertEquals(0, BlockPalette.cell("not_a_block"));
        assertEquals(0x99, (BlockPalette.shade(0x99D0EEF4, 0.5f) >>> 24) & 0xFF);

        StudioVolume vol = new StudioVolume(4, 3, 4);
        assertEquals(-1, vol.set(-1, 0, 0, 1));
        vol.set(1, 2, 3, BlockPalette.cell("bricks"));
        StudioVolume back = StudioVolume.read(vol.write());
        assertEquals(4, back.sizeX());
        assertEquals(3, back.sizeY());
        assertEquals(4, back.sizeZ());
        assertEquals(1, back.count());
        assertEquals(BlockPalette.cell("bricks"), back.get(1, 2, 3));
        assertEquals(0, back.get(0, 0, 0));
        assertFalse(vol.write().contains("air"));

        try {
            StudioVolume.read("ost1\n2 2 2\n0 0 0 not_a_block\n");
            fail("unknown block should be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknown"));
        }
    }

    @Test
    public void slideMergesOncePerCell() {
        Slide2048 row = fresh();
        row.set(0, 0, 2);
        row.set(0, 1, 2);
        row.set(0, 2, 2);
        row.set(0, 3, 2);
        assertTrue(row.move(0, false));
        assertEquals(4, row.at(0, 0));
        assertEquals(4, row.at(0, 1));
        assertEquals(0, row.at(0, 2));
        assertEquals(0, row.at(0, 3));
        assertEquals(8, row.score);

        Slide2048 gap = fresh();
        gap.set(0, 0, 2);
        gap.set(0, 2, 2);
        assertTrue(gap.move(0, false));
        assertEquals(4, gap.at(0, 0));
        assertEquals(0, gap.at(0, 1));
        assertEquals(4, gap.score);

        Slide2048 once = fresh();
        once.set(0, 0, 2);
        once.set(0, 1, 2);
        once.set(0, 2, 4);
        assertTrue(once.move(0, false));
        assertEquals(4, once.at(0, 0));
        assertEquals(4, once.at(0, 1));
        assertEquals(0, once.at(0, 2));
        assertEquals(4, once.score);

        Slide2048 right = fresh();
        right.set(0, 0, 2);
        right.set(0, 1, 2);
        assertTrue(right.move(1, false));
        assertEquals(0, right.at(0, 2));
        assertEquals(4, right.at(0, 3));

        Slide2048 up = fresh();
        up.set(0, 0, 2);
        up.set(1, 0, 2);
        assertTrue(up.move(2, false));
        assertEquals(4, up.at(0, 0));
        assertEquals(0, up.at(1, 0));
    }

    @Test
    public void firstMineClickIsSafeAndAFlagDoesNotOpen() {
        Minefield field = new Minefield(4, 4, 3, 7L);
        field.reveal(1, 1);
        assertFalse(field.mine(1, 1));
        assertTrue(field.open(1, 1));
        assertFalse(field.dead());
        for (int y = 0; y < field.height; y++) {
            for (int x = 0; x < field.width; x++) {
                if (!field.mine(x, y)) {
                    field.reveal(x, y);
                }
            }
        }
        assertTrue(field.won());
        assertFalse(field.dead());

        Minefield flags = new Minefield(5, 5, 3, 3L);
        flags.toggleFlag(0, 0);
        flags.reveal(0, 0);
        assertTrue(flags.flagged(0, 0));
        assertFalse(flags.open(0, 0));
        assertFalse(flags.dead());
    }

    @Test
    public void motifRejectsAWrongPadAndGrowsAfterARound() {
        Motif motif = new Motif();
        motif.script(new int[]{0, 1, 0}, 10_000);
        assertTrue(motif.listening(10_000));
        motif.press(0, 10_000);
        assertEquals(1, motif.heard());
        motif.press(2, 10_000);
        assertTrue(motif.failed());
        assertEquals(1, motif.heard());
        assertEquals(3, motif.length());

        Motif grown = new Motif();
        grown.script(new int[]{1, 2}, 5_000);
        grown.press(1, 5_000);
        grown.press(2, 5_000);
        assertEquals(3, grown.length());
        assertEquals(0, grown.heard());
        assertFalse(grown.failed());
        assertFalse(grown.listening(5_000));
        long span = Motif.NOTE_MS + Motif.GAP_MS;
        assertTrue(grown.listening(5_000 + 360 + 3 * span));

        Motif full = new Motif();
        int[] notes = new int[12];
        for (int i = 0; i < notes.length; i++) {
            notes[i] = i % Motif.PADS;
        }
        full.script(notes, 0);
        for (int note : notes) {
            full.press(note, 0);
        }
        full.press(0, 0);
        assertEquals(12, full.length());
        assertFalse(full.failed());

        Motif play = new Motif();
        long now = 1_000_000L;
        play.start(now);
        assertFalse(play.idle());
        assertEquals(1, play.length());
        int lit = play.lit(now + 10);
        assertTrue(lit >= 0 && lit < Motif.PADS);
        assertFalse(play.listening(now + 10));
    }

    private static Slide2048 fresh() {
        Slide2048 slide = new Slide2048();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                slide.set(row, col, 0);
            }
        }
        slide.score = 0;
        return slide;
    }
}
