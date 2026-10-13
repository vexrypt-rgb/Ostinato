package baritone.bastion;

import org.junit.Test;
import static org.junit.Assert.*;
import static baritone.bastion.FluidPocket.Action.*;
import static baritone.bastion.FluidPocket.decide;

public class FluidPocketTest {
    @Test
    public void swimsUpWhenTheSurfaceIsCloseEnough() {
        assertEquals(SWIM_UP, decide(300, 300, 5, false, 3, true, false, true));
        // 25 blocks deep with 200 air: 290 ticks needed, door instead
        assertEquals(DOOR, decide(200, 300, 25, false, 3, true, false, true));
    }

    @Test
    public void sealedCeilingNeedsAPocket() {
        assertEquals(DOOR, decide(300, 300, -1, false, 1, true, true, true));
        assertEquals(SINK, decide(300, 300, -1, false, 1, false, true, true));
        // no door: nothing else to try
        assertEquals(SWIM_UP, decide(100, 300, -1, false, 0, true, true, true));
    }

    @Test
    public void pocketRefillsThenTunnelsOrLeaves() {
        assertEquals(WAIT, decide(120, 300, -1, true, 0, true, true, true));
        assertEquals(TUNNEL, decide(300, 300, -1, true, 0, true, true, true));
        assertEquals(WAIT, decide(300, 300, -1, true, 0, true, false, true));
        assertEquals(WAIT, decide(300, 300, -1, true, 0, true, true, false));
        assertEquals(SWIM_UP, decide(300, 300, 20, true, 0, true, true, true));
    }

    @Test
    public void neverMinesNextToFluid() {
        assertTrue(FluidPocket.safeToMine(new boolean[]{false, false, false}));
        assertFalse(FluidPocket.safeToMine(new boolean[]{false, true, false}));
    }
}