/*
 * Ostinato: new file (Presto v4.1). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato Presto v4.1: SimTravel resumed a plan solved twenty blocks earlier. presto-v4.1 run 2 lap 13 (laps 1 and 18
 * and run 1 lap 13 the same): stalled at the step up (x 20.70, z 0.70), SimTravel solved "J........" toward (21,0) at
 * yaw -104.4, pressed its first step, and the kinematic controller took the next tick back. At Jump 40>44, which the
 * controller declines, SimTravel found that plan still stored at step 1 (drift() checks from step 2) and replayed it:
 * the old yaw, no jump, off the walkway at x 41.4. Course: {@link TakeoffSlewTest#course()}, floor top y 0.
 */
public class SimTravelStalePlanTest {

    private static final BetterBlockPos STEP_UP = new BetterBlockPos(21, 1, 0), LANDING = new BetterBlockPos(44, 0, 0);

    /** Lap 13, t=4301: SimTravel takes Jump 40>44 from the kinematic controller. */
    private static PlayerSim handover() {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        s.x = 40.338;
        s.y = 0;
        s.z = 0.498;
        s.vx = 0.1531;
        s.vy = -0.0784;
        s.vz = 0.0050;
        s.onGround = true;
        s.sprinting = true;
        return s;
    }

    @Test
    public void onlyTheNextTickForTheSameJumpResumes() {
        assertFalse(SimTravel.stale(100, 101, LANDING, new BetterBlockPos(44, 0, 0)));
        assertTrue("another driver had a tick", SimTravel.stale(100, 102, LANDING, LANDING));
        assertTrue("lap 13: pressed at 4227, resumed at 4301", SimTravel.stale(4227, 4301, STEP_UP, LANDING));
        assertTrue("a different jump", SimTravel.stale(100, 101, STEP_UP, LANDING));
        assertTrue("same tick twice", SimTravel.stale(100, 100, LANDING, LANDING));
        assertTrue("nothing solved", SimTravel.stale(100, 101, null, LANDING));
    }

    /** The defect, replayed: the rest of the step-up plan at its yaw puts the player in the three-block gap. */
    @Test
    public void theStalePlanWalksIntoTheGap() {
        PlayerSim s = handover();
        double minY = 0;
        for (int t = 0; t < 40 && s.y > -2; t++) {
            s.tick(-104.4f, t < 8, t < 8, false); // steps 1-8 of "J........": no jump; then nothing holds the keys
            minY = Math.min(minY, s.y);
        }
        assertTrue("fell: y " + minY + " x " + s.x, minY < -1);
    }

    /** Solved afresh from the same spot, as run 2's successful laps did ("..J"), the jump lands on 44. */
    @Test
    public void aFreshSolveFromThereLands() {
        PlayerSim.World w = TakeoffSlewTest.course();
        PlayerSim s = handover();
        float yaw = SimProbe.yawToward(s.x, s.z, LANDING.x, LANDING.z);
        SimProbe.Result r = SimProbe.search(w, s, yaw, LANDING.x, LANDING.y, LANDING.z);
        assertTrue("found", r.found());
        assertTrue("it jumps", java.util.stream.IntStream.range(0, r.jumps.length).anyMatch(i -> r.jumps[i]));
        for (int t = 0; t < r.jumps.length; t++) {
            s.tick(yaw, true, true, r.jumps[t]);
            assertTrue("t " + t + " y " + s.y + " x " + s.x, s.y > -0.5);
        }
        assertTrue("on the landing: " + s.x + "," + s.y + "," + s.z, SimTravel.onLanding(s, LANDING) || s.onGround && s.x > 44 - PlayerSim.HALF_WIDTH);
    }
}
