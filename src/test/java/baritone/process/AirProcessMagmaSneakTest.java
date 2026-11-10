/*
 * Ostinato 2.0 Phase 10: new file. Not part of upstream Baritone.
 */
package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * B6: the magma check in {@code AirProcess.isActive()} presses SNEAK whoever is in control, so it must release its own
 * press when the player is off magma, and must not touch a SNEAK it did not press.
 */
public class AirProcessMagmaSneakTest {

    @Test
    public void pressesOnMagmaEveryTick() {
        assertEquals(Boolean.TRUE, AirProcess.magmaSneak(true, false));
        assertEquals(Boolean.TRUE, AirProcess.magmaSneak(true, true));
    }

    @Test
    public void releasesItsOwnPressOnceOffMagma() {
        // a run of ticks: two on magma, then off; the idle player was left sneaking before the fix
        boolean pressed = false;
        Boolean[] seen = new Boolean[4];
        boolean[] magma = {true, true, false, false};
        for (int t = 0; t < magma.length; t++) {
            seen[t] = AirProcess.magmaSneak(magma[t], pressed);
            if (seen[t] != null) pressed = seen[t];
        }
        assertEquals(Boolean.TRUE, seen[0]);
        assertEquals(Boolean.TRUE, seen[1]);
        assertEquals(Boolean.FALSE, seen[2]);
        assertNull("released once, then left alone", seen[3]);
    }

    @Test
    public void leavesAnotherWritersSneakAlone() {
        assertNull(AirProcess.magmaSneak(false, false));
    }
}
