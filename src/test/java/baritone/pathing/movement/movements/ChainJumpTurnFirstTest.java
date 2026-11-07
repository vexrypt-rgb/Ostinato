/*
 * Ostinato: new file (Presto v4.1). Not part of upstream Baritone.
 */
package baritone.pathing.movement.movements;

import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.kinematic.TakeoffSlewTest;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato Presto v4 fix 7: a chain jump turns on the spot before its plan's keys go down. presto-v4-fix6 lap 4,
 * ChainJump 8>18: the walk back to the run-up start left the look west (88.8) and the player still sliding west; the
 * plan east (-90) pressed W and jump at once, the look turned 32° a tick, and the jump flew west into the one-block gap
 * behind (fell at x 5.47). Course: {@link TakeoffSlewTest#course()}, floor top y 0, the one-block gap at x 5.
 */
public class ChainJumpTurnFirstTest {

    /** The silent look's step: the short way from the last sent yaw, at most 32°. */
    private static float step(float sent, float target) {
        float d = (target - sent) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return sent + Math.max(-32f, Math.min(32f, d));
    }

    /** presto-v4-fix6 lap 4, t=1321: run-up start reached, still moving west. */
    private static PlayerSim start() {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        s.x = 6.501;
        s.y = 0;
        s.z = 0.497;
        s.vx = -0.0642;
        s.vz = 0.0012;
        s.vy = -0.0784;
        s.onGround = true;
        return s;
    }

    @Test
    public void theRecordedStartMustTurnFirst() {
        assertTrue(MovementChainJump.turnFirst(88.8f, -90f));
    }

    @Test
    public void facingThePlanGoesAtOnce() {
        assertFalse(MovementChainJump.turnFirst(-90f, -90f));
        assertFalse(MovementChainJump.turnFirst(-60f, -90f));
        assertFalse("wrapped", MovementChainJump.turnFirst(270f, -90f));
    }

    /** The defect: the plan's first keys (W + jump) pressed while the look is still turning. */
    @Test
    public void pressingMidTurnJumpsBackIntoTheGap() {
        PlayerSim s = start();
        float look = 88.8f;
        double minY = 0;
        for (int t = 0; t < 30 && s.y > -2; t++) {
            look = step(look, -90f);
            s.tick(look, true, t > 0, t == 0);
            minY = Math.min(minY, s.y);
        }
        assertTrue("fell into the gap: y " + minY + " x " + s.x, minY < -1);
    }

    /** Gated: stand while turnFirst, then the same keys jump east and come down on the course ahead. */
    @Test
    public void turningFirstJumpsTheRightWay() {
        PlayerSim s = start();
        float look = 88.8f;
        int waited = 0, t0 = -1;
        for (int t = 0; t < 30; t++) {
            look = step(look, -90f);
            boolean go = t0 >= 0 || !MovementChainJump.turnFirst(look, -90f);
            if (go && t0 < 0) t0 = t;
            if (!go) waited++;
            boolean air = !s.onGround;
            s.tick(look, go, go && t > t0, go && t == t0);
            assertTrue("t " + t + " y " + s.y + " x " + s.x, s.y > -0.5);
            if (air && s.onGround) {
                break;
            }
        }
        assertTrue("it waited out the turn: " + waited, waited >= 3 && waited <= 6);
        assertTrue("jumped and came down", t0 >= 0 && s.onGround && Math.abs(s.y) < 1e-6);
        assertTrue("east of the start, not in the gap behind: x " + s.x, s.x > 6.6);
    }
}
