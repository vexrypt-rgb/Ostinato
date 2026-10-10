package baritone.bastion;

import org.junit.Test;

import static baritone.bastion.BastionLava.Escape.*;
import static baritone.bastion.BastionLava.choose;
import static org.junit.Assert.assertEquals;

public class BastionLavaTest {
    @Test
    public void doorWhenDeepOrFar() {
        assertEquals(DOOR, choose(1, 3, 20, true, true, true));      // deep: door even with the shore close
        assertEquals(DOOR, choose(-1, 1, 20, true, true, true));     // no shore in sight
        assertEquals(DOOR, choose(5, 1, 20, true, true, true));      // shore too far to swim
        assertEquals(DOOR, choose(2, 1, 5, true, true, true));       // too hurt to swim even two blocks
    }

    @Test
    public void swimWhenShallowAndClose() {
        assertEquals(SWIM, choose(1, 1, 20, true, true, true));
    }

    @Test
    public void needsDoorAndFloor() {
        assertEquals(SWIM, choose(5, 3, 20, false, true, true));
        assertEquals(SWIM, choose(5, 3, 20, true, false, true));
        assertEquals(PILLAR, choose(-1, 3, 20, true, false, true));
        assertEquals(NONE, choose(-1, 3, 20, false, false, false));
    }

    @Test
    public void descentStepsAndDoors() {
        // feet in the lake (0), floor layer (1) and below (2,3) clean netherrack: break twice, head ends in the floor layer
        boolean[] clean = {false, true, true, true}, solid = {false, true, true, true};
        assertEquals(2, BastionLava.descentSteps(clean, solid));
        assertEquals(3, BastionLava.doorsNeeded(2));
        assertEquals(true, BastionLava.canDescend(2, 3, true));
        assertEquals(false, BastionLava.canDescend(2, 2, true));
        assertEquals(false, BastionLava.canDescend(2, 5, false));
        // floor layer touches lava on a side (lava pocket in it): one more step down
        assertEquals(3, BastionLava.descentSteps(new boolean[]{false, false, true, true, true}, new boolean[]{false, true, true, true, true}));
        // a cave under the floor: no descent
        assertEquals(-1, BastionLava.descentSteps(new boolean[]{false, true, true, true}, new boolean[]{false, true, false, true}));
        assertEquals(-1, BastionLava.doorsNeeded(-1));
    }

    @Test
    public void sealOnlyWhenBothCellsClean() {
        assertEquals(true, BastionLava.sealNow(true, true));
        assertEquals(false, BastionLava.sealNow(false, true));
    }

    @Test
    public void edgeRule() {
        assertEquals(false, EdgeCost.deadly(5, 2));  // floor 2 down before the lava
        assertEquals(false, EdgeCost.deadly(2, -1)); // lava right below: a step, not a drop
        assertEquals(true, EdgeCost.deadly(6, -1));  // 6 of air onto lava
        assertEquals(true, EdgeCost.deadly(-1, -1)); // nothing in range: void
    }
}