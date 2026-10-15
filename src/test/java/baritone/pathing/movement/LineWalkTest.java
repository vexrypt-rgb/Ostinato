package baritone.pathing.movement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The eight knight corridors are fixed. A* must see the same cells it did when they were built per call. */
public class LineWalkTest {

    @Test
    public void knightCorridorIsTheOpenRectangleAndIsReused() {
        int[][] a = LineWalk.corridor(2, -1);
        int[][] b = LineWalk.corridor(2, -1);
        assertSame(a, b);
        assertEquals(5, a.length);
        assertTrue(has(a, 2, -1));
        assertTrue(has(a, 1, 0));
        assertTrue(has(a, 2, 0));
        assertTrue(has(a, 1, -1));
        assertTrue(has(a, 0, -1));
        assertEquals(5, LineWalk.corridor(-1, 2).length);
        assertSame(LineWalk.corridor(-1, 2), LineWalk.corridor(-1, 2));
    }

    private static boolean has(int[][] cells, int x, int z) {
        for (int[] c : cells) {
            if (c[0] == x && c[1] == z) {
                return true;
            }
        }
        return false;
    }
}
