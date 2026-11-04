/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.pathing.movement.movements;

import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.kinematic.TakeoffSlewTest;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: the Presto course's 4 block gap (take-off block x 48, landing x 53, the ceiling over x 36-48) as
 * MovementParkour's own jump sim sees it. Live, when the takeoff driver handed the jump back, a Traverse ran the player
 * into it at full sprint, no back-up happened, and the jump on the last tick on the block hit the landing's face
 * (presto-v4-trace1 laps 2, 4, 5, 8: take-off x 49.32-49.33, all four into the pit at x 52.70).
 */
public class ParkourFourGapTest {

    private static final float EAST = -90f; // yaw along +x

    private static boolean onLanding(double x, double z) {
        int bx = PlayerSim.floor(x), bz = PlayerSim.floor(z);
        return bz == 0 && bx >= 53 && bx <= 58;
    }

    private static PlayerSim state(double x, double z, double vx, double vz, boolean sprinting) {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        s.x = x;
        s.y = 0;
        s.z = z;
        s.vx = vx;
        s.vy = -0.0784; // resting on the ground, as every live trace line has it
        s.vz = vz;
        s.onGround = true;
        s.sprinting = sprinting;
        return s;
    }

    /** First jump delay (ticks from {@code start}) that lands on the far side, or -1: MovementParkour.runUpLands. */
    private static int firstLanding(PlayerSim start) {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        for (int d = 0; d < 40; d++) {
            s.copyFrom(start);
            if (MovementParkour.simJump(s, EAST, d, 0, ParkourFourGapTest::onLanding)) {
                return d;
            }
        }
        return -1;
    }

    /** The run the Traverse delivered (lap 2 t=793, the tick Parkour 48>53 took over): no tick of it lands. */
    @Test
    public void theRunATraverseDeliversCannotLand() {
        assertEquals(-1, firstLanding(state(48.238, 0.539, 0.1380, -0.0164, true)));
        // nor from the tick before take-off (t=796)
        assertEquals(-1, firstLanding(state(49.052, 0.510, 0.1507, -0.0024, true)));
    }

    /** Where the rerun's back-up stops (0.3 behind the run-up block's centre) a fresh sprint has a landing tick. */
    @Test
    public void aRunFromTheBackUpSpotLands() {
        int d = firstLanding(state(46.2, 0.5, 0, 0, false));
        System.out.println("PARKOUR-4GAP from 46.2 standing: first landing jump delay " + d);
        assertTrue(d >= 0);
    }

    /** The jump-timing rule (wait while a tick later still lands) ends on a tick that lands. */
    @Test
    public void latestLandingTickLands() {
        PlayerSim start = state(46.2, 0.5, 0, 0, false);
        int last = -1;
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        for (int d = 0; d < 40; d++) {
            s.copyFrom(start);
            if (MovementParkour.simJump(s, EAST, d, 0, ParkourFourGapTest::onLanding)) {
                last = d;
            }
        }
        System.out.println("PARKOUR-4GAP from 46.2 standing: last landing jump delay " + last);
        assertTrue(last >= 0);
        s.copyFrom(start);
        assertFalse("a tick later is off the block or short", MovementParkour.simJump(s, EAST, last + 1, 0, ParkourFourGapTest::onLanding));
        // The step rule (jump once this step plus the next reaches a block past the take-off centre) fires a tick
        // earlier, at x 49.23: the next tick's x 49.51 is past the block, yet still a take-off, since the ground flag
        // is set by the vertical move before the horizontal one. From there the box hits the landing's face.
        s.copyFrom(start);
        assertFalse("the step rule's tick falls short", MovementParkour.simJump(s, EAST, last - 1, 0, ParkourFourGapTest::onLanding));
    }
}
