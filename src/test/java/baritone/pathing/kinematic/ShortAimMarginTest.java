/*
 * Ostinato: new file (Presto v4.1). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato Presto v4 fix 6: the "short aim" rollout (steer at the line right beside the player for four ticks) went
 * without the barely-lands margin check that every other long-jump plan gets. presto-v4-fix5 lap 9 (Presto on) took
 * the four-block gap 48>53 with it from the run-up's edge, 0.18 beside the line, and hit the landing block's face at
 * x 52.70, z 0.81.
 * <p>
 * The controller can't be built without a client (its rollouts read the world and the camera model through the
 * player context), so this replays that take-off in PlayerSim on the course ({@link TakeoffSlewTest#course()}, floor
 * top y 0, the low ceiling included) with the controller's own margin geometry ({@link KinematicController#marginShifts}).
 * It pins down what the margin rule has to catch there: the short aim's veer costs the distance (the sim reproduces
 * the live x 52.70), and even the line-aimed jump from that spot lands only with no slack, so one margin shift misses.
 * Whether drive() applies the rule to the short-aim candidate is covered live (fix7-9: no four-gap falls in 72 laps).
 */
public class ShortAimMarginTest {

    // presto-v4-fix5 lap 9, t=3095: the take-off tick (feet at the floor top, ground speed along +x)
    private static final double X = 48.948, Z = 0.324, VX = 0.2606, VZ = -0.0141;

    /** The controller's aim (KinematicController.aim, no wander) along the course's line z = 0.5: shortAim looks 0.4 ahead, else 1.6. */
    private static float aim(double x, double z, boolean shortAim) {
        double tx = x + (shortAim ? 0.4 : 1.6), tz = 0.5;
        return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }

    /** The rollout's flight of a jump taken now: short aim for its first four ticks when asked. Returns the sim after touchdown or 40 ticks. */
    private static PlayerSim fly(double x, double z, boolean shortAim) {
        PlayerSim s = new PlayerSim(TakeoffSlewTest.course());
        s.x = x;
        s.y = 0;
        s.z = z;
        s.vx = VX;
        s.vz = VZ;
        s.vy = -0.0784;
        s.onGround = true;
        s.sprinting = true;
        for (int t = 0; t < 40; t++) {
            boolean air = !s.onGround;
            s.tick(aim(s.x, s.z, shortAim && t < 4), true, true, t == 0);
            if (air && s.onGround || s.y < -1) {
                break;
            }
        }
        return s;
    }

    private static boolean landed(PlayerSim s) {
        return s.onGround && Math.abs(s.y) < 1e-6 && s.x - PlayerSim.HALF_WIDTH < 58 && s.x + PlayerSim.HALF_WIDTH > 53;
    }

    @Test
    public void theRecordedShortAimIsTwentyFourDegreesOff() {
        // the trace's look target that tick was -66.2 against a line at -90
        assertEquals(-66.2, aim(X, Z, true), 0.1);
    }

    /** The short aim veers toward the line and past it, and the sideways component is distance the gap needed. */
    @Test
    public void theRecordedShortAimJumpFallsShortAsItDidLive() {
        PlayerSim s = fly(X, Z, true);
        assertFalse("short of the landing: " + s.x + "," + s.y, landed(s));
        assertEquals("against the landing block's face, as live", 53 - PlayerSim.HALF_WIDTH, s.x, 0.01);
        assertEquals("veered past the line, as live (0.81)", 0.81, s.z, 0.05);
    }

    /** From the recorded spot the line-aimed jump lands only just: a quarter block later it misses. No plan holds the margin there. */
    @Test
    public void fromTheRecordedSpotNoPlanHoldsTheMargin() {
        PlayerSim nominal = fly(X, 0.5, false);
        assertTrue("the line-aimed jump lands: " + nominal.x + "," + nominal.y, landed(nominal));
        assertTrue("with under a tenth of a block to spare: " + nominal.x, nominal.x - PlayerSim.HALF_WIDTH - 53 < 0.1);
        boolean allHold = true;
        StringBuilder where = new StringBuilder();
        for (double[] d : KinematicController.marginShifts(VX, 0)) {
            PlayerSim s = fly(X + d[0], 0.5 + d[1], false);
            where.append(String.format(java.util.Locale.ROOT, " (%.2f,%.2f)->%.2f,%.2f", d[0], d[1], s.x, s.y));
            allHold &= landed(s);
        }
        assertFalse("a margin shift misses, so holdsWithMargin rejects it:" + where, allHold);
    }

    /** Taking off nearer the edge, on the line, holds every shift: the rule does not reject the gap itself. */
    @Test
    public void nearerTheEdgeOnTheLineHoldsTheMargin() {
        double x = 48.948 + 0.3; // 0.3 further along: the box (48.95..49.55) still has block 48 under its back edge
        for (double[] d : KinematicController.marginShifts(VX, 0)) {
            PlayerSim s = fly(x + d[0], 0.5 + d[1], false);
            assertTrue("shift " + d[0] + "," + d[1] + " -> " + s.x + "," + s.y, landed(s));
        }
    }

    @Test
    public void shiftsAreBackAndToBothSides() {
        double[][] d = KinematicController.marginShifts(0.3, 0);
        assertArrayEquals(new double[]{-0.25, 0}, d[0], 1e-9);
        assertArrayEquals(new double[]{0, KinematicController.LATERAL_MARGIN}, d[1], 1e-9);
        assertArrayEquals(new double[]{0, -KinematicController.LATERAL_MARGIN}, d[2], 1e-9);
    }
}
