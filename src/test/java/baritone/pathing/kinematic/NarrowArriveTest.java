package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ostinato: flying onto a narrow landing steers for its middle instead of braking short. */
public class NarrowArriveTest {
    @Test
    public void candleTopIsTheFloorNotAFall() {
        assertEquals(10 / 16.0, KinematicController.dropFor(6 / 16.0), 1e-9); // candle: feet rest 0.625 below the cell floor
        assertEquals(0, KinematicController.dropFor(1.0), 1e-9);
        assertEquals(0, KinematicController.dropFor(0), 1e-9);
    }

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
