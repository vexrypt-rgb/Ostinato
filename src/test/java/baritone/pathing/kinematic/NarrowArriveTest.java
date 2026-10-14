package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ostinato: flying onto a narrow landing steers for its middle instead of braking short. */
public class NarrowArriveTest {
    @Test
    public void farAndSlowKeepsGoing() {
        assertEquals(1, KinematicController.airArrive(1.0, 0.1));
    }

    @Test
    public void farAndFastCoasts() {
        assertEquals(0, KinematicController.airArrive(1.0, 0.3));
    }

    @Test
    public void overTheMiddleBrakes() {
        assertEquals(-1, KinematicController.airArrive(0.1, 0.2));
    }
}
