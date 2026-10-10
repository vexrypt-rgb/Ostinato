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
}