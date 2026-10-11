package baritone.bastion;

import org.junit.Test;
import static org.junit.Assert.*;

public class ItemThrowTest {
    static final ItemThrow.Floor FLAT = (x, z) -> 64;

    @Test
    public void flatThrowTravelsAboutTwoBlocks() {
        double[] l = ItemThrow.land(0.5, 64 + 1.62, 0.5, 0, 0, FLAT);
        assertEquals(64, l[1], 1e-9);
        double d = l[2] - 0.5;
        assertTrue("dist " + d, d > 1.5 && d < 3.5);
        assertEquals(0.5, l[0], 1e-9);
    }

    @Test
    public void lookingDownDropsAtFeet() {
        double[] l = ItemThrow.land(0.5, 65.62, 0.5, 0, 90, FLAT);
        assertEquals(0.5, l[2], 0.3);
    }

    @Test
    public void aimsIntoFarCornerOfHole() {
        // bot stands at (0.5, 0.5); 1x1 hole at block x=1..2, z=1..2, floor 2 down; aim at the far corner (1.8, 1.8)
        ItemThrow.Floor hole = (x, z) -> (x >= 1 && x < 2 && z >= 1 && z < 2) ? 62 : 64;
        float[] a = ItemThrow.aim(0.5, 65.62, 0.5, 1.8, 1.8, hole);
        double[] l = ItemThrow.land(0.5, 65.62, 0.5, a[0], a[1], hole);
        assertEquals(62, l[1], 1e-9);
        assertTrue(l[0] >= 1 && l[0] < 2 && l[2] >= 1 && l[2] < 2);
        // landed past the hole centre, on the far side from the bot
        assertTrue(l[0] + l[2] > 3.0);
    }
}
