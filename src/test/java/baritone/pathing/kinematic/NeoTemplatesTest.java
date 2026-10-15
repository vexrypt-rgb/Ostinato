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
}
