/*
 * Ostinato: new file (Presto v4.1). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import baritone.pathing.physics.PhysicsTravel;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato Presto v4.1: SimTravel pressed a fresh plan while the look was still turning. presto-v4.1 final1 lap 21:
 * jolted back to x 34.1 inside Jump 31>36, Baritone's Jump turned round and walked west toward its run-up; at t=6921
 * (x 33.341, moving west, the sent yaw 89.5) SimTravel solved east (-89.5) and held W and sprint at once. The move yaw
 * turned 32° a tick through south and the player went off the walkway's side at z 1.5. Lap 22 the same, turning the
 * other way (z -0.48). Course: {@link TakeoffSlewTest#course()}, floor top y 0, the step up to the 28-31 floor at x 32.
 */
public class SimTravelTurnFirstTest {

    private static final float PLAN = -89.5f, SENT = 89.5f;

    /** The silent look's step: the short way from the last sent yaw, at most 32°. */
    private static float step(float sent, float target) {
        float d = (target - sent) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return sent + Math.max(-32f, Math.min(32f, d));
    }

    private static PlayerSim start() {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        s.x = 33.341;
        s.y = 0;
        s.z = 0.470;
        s.vx = -0.1158;
        s.vz = 0.0013;
        s.vy = -0.0784;
        s.onGround = true;
        return s;
    }

    @Test
    public void theRecordedHandoverIsOffPlan() {
        assertTrue(PhysicsTravel.offPlan(SENT, PLAN));
        assertTrue("lap 22's sent yaw", PhysicsTravel.offPlan(92.1f, -90.8f));
        assertFalse(PhysicsTravel.offPlan(-60f, PLAN));
        // final1 lap 20 (Presto off), the x 21.6 side fall: stalled at the step up, the controller turned to recentre
        // (move yaw 180.3); SimTravel pressed W and jump toward -90.1 at once, flew to z -0.3 and fell at x 21.56
        assertTrue(PhysicsTravel.offPlan(180.3f, -90.1f));
    }

    /** final1 lap 20, t=6627, replayed: standing at the step's face, W and jump with the look a quarter turn off. */
    @Test
    public void jumpingTheStepWhileTurningGoesOffTheSide() {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        s.x = 20.700;
        s.y = 0;
        s.z = 0.501;
        s.vy = -0.0784;
        s.onGround = true;
        float look = 180.3f;
        double minZ = s.z;
        for (int t = 0; t < 20 && s.y > -2; t++) {
            look = step(look, -90.1f);
            s.tick(look, true, true, t == 0);
            minZ = Math.min(minZ, s.z);
        }
        assertTrue("went toward -z off the walkway: min z " + minZ + " y " + s.y, minZ < -0.2);
    }

    /** The defect, replayed: the keys go down with the look turning, the walk swings off the side. */
    @Test
    public void pressingWhileTurningWalksOffTheSide() {
        PlayerSim s = start();
        float look = SENT;
        double maxOff = 0;
        for (int t = 0; t < 12 && s.y > -2; t++) {
            look = step(look, PLAN);
            s.tick(look, true, true, false);
            maxOff = Math.max(maxOff, Math.abs(s.z - 0.5));
        }
        assertTrue("off the one-wide walkway: z off by " + maxOff + ", y " + s.y, maxOff > 0.8 || s.y < -1);
    }

    /** Gated: stand while off plan, then the same keys run east along the walkway. */
    @Test
    public void turningFirstStaysOnTheWalkway() {
        PlayerSim s = start();
        float look = SENT;
        int waited = 0;
        for (int t = 0; t < 16; t++) {
            look = step(look, PLAN);
            boolean go = !PhysicsTravel.offPlan(look, PLAN);
            if (!go) waited++;
            s.tick(look, go, go, false);
            assertTrue("t " + t + " z " + s.z + " y " + s.y, Math.abs(s.z - 0.5) < 0.2 && s.y > -0.5);
        }
        assertTrue("it waited out the turn: " + waited, waited >= 3 && waited <= 5);
        assertTrue("heading east: x " + s.x, s.x > 34);
    }
}
