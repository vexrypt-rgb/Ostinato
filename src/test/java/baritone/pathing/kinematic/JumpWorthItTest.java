package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.*;

/** Kinematic travel jumps only when it has to or when it clearly pays. */
public class JumpWorthItTest {

    @Test
    public void flatRunDoesNotHop() {
        // a sprint-jump scored a little further than running: run
        assertFalse(KinematicController.jumpWorthIt(3.6, 3.4, 0));
    }

    @Test
    public void jumpWhenRunningGoesNowhere() {
        // a gap or a step: running makes no progress
        assertTrue(KinematicController.jumpWorthIt(2.0, 0.02, 0));
        assertTrue(KinematicController.jumpWorthIt(2.0, -1e9, 0));
    }

    @Test
    public void jumpWhenClearlyFaster() {
        assertTrue(KinematicController.jumpWorthIt(4.5, 3.4, 0));
    }
}