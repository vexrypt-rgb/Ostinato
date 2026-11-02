/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.pathing.physics;

import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.kinematic.TakeoffSlewTest;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: PhysicsTravel turns before it walks. presto-v4-fix4 laps 2 and 5 started facing west (yaw 88.6) on a path
 * east (-90); the plan's keys went down at once while the move's yaw swung 32° a tick through south, and the walk left
 * the one-wide walkway sideways before the turn was done.
 */
public class PhysicsTurnGateTest {

    /** The silent look's step: the short way from the last sent yaw, at most 32°. */
    private static float aimStep(float sent, float target) {
        float d = (target - sent) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return sent + Math.max(-32f, Math.min(32f, d));
    }

    private static PlayerSim start() {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        s.x = 0.5;
        s.y = 0;
        s.z = 0.5;
        s.vy = -0.0784;
        s.onGround = true;
        return s;
    }

    /** Keys down through the turn: the defect, off the walkway's side within six ticks. */
    @Test
    public void walkingThroughTheTurnLeavesTheWalkway() {
        PlayerSim s = start();
        float sent = 88.6f;
        double maxZ = 0;
        for (int t = 0; t < 6; t++) {
            sent = aimStep(sent, -90f);
            s.tick(sent, true, true, false);
            maxZ = Math.max(maxZ, s.z);
        }
        assertTrue("z " + maxZ, maxZ + PlayerSim.HALF_WIDTH > 1.2);
    }

    /** Gated: no key until the move's yaw is within the gate, then the walk stays on the walkway. */
    @Test
    public void turningFirstStaysOn() {
        PlayerSim s = start();
        float sent = 88.6f;
        int waited = 0;
        for (int t = 0; t < 20; t++) {
            sent = aimStep(sent, -90f);
            boolean go = !PhysicsTravel.offPlan(sent, -90f);
            if (!go) waited++;
            s.tick(sent, go, go, false);
            assertTrue("z " + s.z + " at " + t, Math.abs(s.z - 0.5) < 0.2 && s.onGround);
        }
        assertTrue("it waited out the turn", waited >= 3 && waited <= 5);
        assertTrue("and then went east", s.x > 3);
    }

    @Test
    public void gateWraps() {
        assertFalse(PhysicsTravel.offPlan(-90f, 270f));
        assertFalse(PhysicsTravel.offPlan(-60f, -90f));
        assertTrue(PhysicsTravel.offPlan(-44f, -90f));
        assertTrue(PhysicsTravel.offPlan(88.6f, -90f));
    }
}
