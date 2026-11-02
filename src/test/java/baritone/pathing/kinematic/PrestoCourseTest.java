/*
 * Ostinato: new file (Presto v4.1). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import org.junit.Test;

import static baritone.pathing.kinematic.PrestoCourse.F;
import static org.junit.Assert.*;

/**
 * Ostinato Presto v4.1: the live A/B harness's jolt. The v4 trigger (client past x 7.2) pulled the server's player,
 * a tick or two behind, back to x 5.1-5.7: onto the one-block gap's edge or over it (presto-v4-fix7 lap 22, fix8 lap
 * 21, fix9 laps 20 and 23 fell there, Presto off and on). The first v4.1 trigger, 9.2, jolted the player in mid-jump
 * (run 1 laps 20-22 fell). Safe jolts now happen only standing, onto verified walkway at the same level.
 */
public class PrestoCourseTest {

    private static final double FEET = F + 1, Z = 0.5;

    @Test
    public void theOldTriggerPulledIntoTheGap() {
        // the recorded fall spots: x 5.70 (fix9 laps 20, 23), from a server x of 7.2
        assertTrue(Double.isNaN(PrestoCourse.safeJoltX(7.2, FEET, Z, true)));
        assertFalse("box half over the gap at 5.7", PrestoCourse.supported(5.7, FEET, Z));
        assertFalse("edge at 6.1: the box's back half over the gap", PrestoCourse.supported(6.1, FEET, Z));
    }

    /** presto-v4.1 run 1 laps 20-22: the server's player at the first v4.1 trigger was in the air over the two-block gap. */
    @Test
    public void aJoltInFlightIsNeverSafe() {
        assertTrue("lap 21: server 9.273, feet +1.001, in the air", Double.isNaN(PrestoCourse.safeJoltX(9.273, FEET + 1.001, 0.506, false)));
        assertTrue("lap 20: server 9.384, feet +0.420", Double.isNaN(PrestoCourse.safeJoltX(9.384, FEET + 0.42, 0.61, false)));
        assertTrue("on the ground is not enough mid-air", Double.isNaN(PrestoCourse.safeJoltX(35.0, FEET + 0.5, Z, true)));
        assertTrue("airborne over solid floor", Double.isNaN(PrestoCourse.safeJoltX(35.0, FEET, Z, false)));
    }

    /**
     * Why: run 1 lap 21's jolt, replayed. The pull-back keeps the jump's speed and height but takes away 1.5 of its
     * distance. With forward and sprint held it drops into the two-block gap against the 11-13 floor's face (x 10.70);
     * live, the controller let go and it came down on the 6-8 floor's lip (x 9.30) and fell. Either way it is short of
     * the floor it was jumping to. Course: {@link TakeoffSlewTest#course()}, floor y 0.
     */
    @Test
    public void aMidJumpPullBackComesDownAtTheGap() {
        PlayerSim jolted = new PlayerSim(TakeoffSlewTest.course());
        jolted.x = 9.273 - PrestoCourse.JOLT_BACK;
        jolted.y = 1.001;
        jolted.z = 0.506;
        jolted.vx = 0.3049;
        jolted.vy = 0.0831;
        jolted.vz = 0.0019;
        jolted.sprinting = true;
        jolted.onGround = false;
        for (int t = 0; t < 40 && !jolted.onGround && jolted.y > -2; t++) {
            jolted.tick(-90f, true, true, false);
        }
        assertTrue("short of the 11-13 floor it was jumping to: x " + jolted.x + " y " + jolted.y, jolted.x - PlayerSim.HALF_WIDTH < 11);
        assertFalse("not wholly on the walkway: x " + jolted.x, PrestoCourse.supported(jolted.x, F + 1 + jolted.y, jolted.z));
    }

    @Test
    public void theSafeTriggerIsOnTheLongFloor() {
        // standing anywhere from the trigger to the window's end, the pull-back lands on the 32-40 floor at one level
        for (double sx = PrestoCourse.SAFE_JOLT_X; sx <= PrestoCourse.SAFE_JOLT_X + PrestoCourse.JOLT_WINDOW + 1e-9; sx += 0.1) {
            double d = PrestoCourse.safeJoltX(sx, FEET, Z, true);
            assertFalse("server x " + sx, Double.isNaN(d));
            assertTrue(d - PrestoCourse.HALF_WIDTH >= 32 && d + PrestoCourse.HALF_WIDTH <= 41);
            assertEquals(F, PrestoCourse.top((int) Math.floor(d)));
        }
    }

    @Test
    public void tooFarAlongIsNotSafe() {
        // standing on the 32-40 floor's last block, the box is already over the three-block gap's edge
        assertTrue(Double.isNaN(PrestoCourse.safeJoltX(40.8, FEET, Z, true)));
        assertFalse(Double.isNaN(PrestoCourse.safeJoltX(40.65, FEET, Z, true)));
        // and a pull-back that would reach back over the step down at 32 is refused
        assertTrue(Double.isNaN(PrestoCourse.safeJoltX(33.7, FEET, Z, true)));
    }

    @Test
    public void supportNeedsOneLevelTheWalkwayAndAShortDrop() {
        assertTrue(PrestoCourse.supported(7.5, FEET, Z));
        assertFalse("off the walkway's side", PrestoCourse.supported(7.5, FEET, 0.85));
        assertFalse("feet under the floor", PrestoCourse.supported(7.5, FEET - 0.5, Z));
        assertTrue("a jump's apex over the floor", PrestoCourse.supported(7.5, FEET + 1.25, Z));
        assertFalse("too high to drop onto it", PrestoCourse.supported(7.5, FEET + 1.6, Z));
        assertFalse("straddling the step up at 21", PrestoCourse.supported(21.0, FEET + 1, Z));
        assertTrue("on the raised part", PrestoCourse.supported(22.5, FEET + 1, Z));
    }

    @Test
    public void geometryMatchesTheCourse() {
        int[][] gaps = {{5, 5}, {9, 10}, {14, 16}, {25, 27}, {41, 43}, {49, 52}};
        for (int[] g : gaps) {
            for (int x = g[0]; x <= g[1]; x++) assertEquals("gap at " + x, Integer.MIN_VALUE, PrestoCourse.top(x));
            assertNotEquals(Integer.MIN_VALUE, PrestoCourse.top(g[0] - 1));
            assertNotEquals(Integer.MIN_VALUE, PrestoCourse.top(g[1] + 1));
        }
        assertEquals(F + 1, PrestoCourse.top(22));
        assertTrue(PrestoCourse.solid(40, F + 4, 0) && PrestoCourse.solid(48, F + 4, -1) && !PrestoCourse.solid(49, F + 4, 0));
        assertTrue("pit floor", PrestoCourse.solid(42, F - 3, 3));
        // the sim's copy of the course (floor top y 0) agrees column by column
        PlayerSim.World w = TakeoffSlewTest.course();
        java.util.List<double[]> boxes = new java.util.ArrayList<>();
        for (int x = 0; x <= 58; x++) {
            boxes.clear();
            w.collect(x + 0.4, -0.05, 0.4, x + 0.6, 0.95, 0.6, boxes);
            double top = boxes.stream().mapToDouble(b -> b[4]).max().orElse(Double.NEGATIVE_INFINITY);
            int t = PrestoCourse.top(x);
            assertEquals("x " + x, t == Integer.MIN_VALUE ? Double.NEGATIVE_INFINITY : t - F, top, 1e-9);
        }
    }
}
