package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Ostinato: neo templates (a jump round the end of a wall) that the planner can offer. */
public class NeoTemplatesTest {
    private static boolean has(int a, int dy) {
        for (JumpTemplates.Template t : JumpTemplates.ALL) if (t.neo && t.a == a && t.dy == dy && t.b == 0) return true;
        return false;
    }

    @Test
    public void twoWallFlatNeo() {
        assertTrue(has(3, 0));
    }

    @Test
    public void oneWallDownNeo() {
        assertTrue(has(2, -1));
    }

    @Test
    public void oneWallUpNeoFromAStandstill() {
        // off a single block, up onto the block right behind a one-thick wall: the arc swings clear of the wall at a=1
        boolean found = false;
        for (JumpTemplates.Template t : JumpTemplates.ALL) {
            if (t.a != 2 || t.dy != 1 || t.b != 0 || t.runUp != 0) continue;
            boolean clear = true;
            for (int[] c : t.cells) clear &= !(c[0] == 1 && c[2] == 0);
            found |= clear;
        }
        assertTrue(found);
    }

    @Test
    public void roundAPillarWithAGapEitherSide() {
        // take-off at 0, air at 1, the pillar at 2, air at 3, landing at 4: the arc swings clear of the pillar's side
        boolean found = false;
        for (JumpTemplates.Template t : JumpTemplates.ALL) {
            if (t.a != 4 || t.dy != 0 || t.b != 0 || t.runUp != 0) continue;
            boolean wide = false;
            for (int[] c : t.cells) wide |= c[0] == 2 && c[2] != 0;
            boolean clear = true;
            for (int[] c : t.cells) clear &= !(c[0] == 2 && c[2] == 0 && c[1] >= 0);
            found |= wide && clear;
        }
        assertTrue(found);
    }
}
