/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: the kinematic controller leaves a reversal to Baritone's movement. presto-v4-fix2 laps 3 and 15: the
 * one-block gap's walk-back left the look west (yaw 454, i.e. 94; 86.5), the path ran east (-92.7; -88.3), and the
 * controller's jump mid-turn flew off the walkway's side.
 */
public class KinematicTurnTest {

    @Test
    public void reversalIsDeclined() {
        assertTrue(KinematicController.turningAround(454.0f, -92.7f));
        assertTrue(KinematicController.turningAround(86.5f, -88.3f));
    }

    @Test
    public void ordinarySteeringIsNot() {
        assertFalse("on the line", KinematicController.turningAround(-90f, -90f));
        assertFalse("a 60 degree corner", KinematicController.turningAround(-90f, -30f));
        assertFalse("unwrapped yaws", KinematicController.turningAround(630f, -90f));
        assertFalse("just inside the limit", KinematicController.turningAround(0f, 89.9f));
        assertTrue("just past it", KinematicController.turningAround(0f, -90.1f));
    }
}
