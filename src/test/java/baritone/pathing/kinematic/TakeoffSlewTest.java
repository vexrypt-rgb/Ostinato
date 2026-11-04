/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: the standing take-off's plan has to survive the 32° look cap. The Presto course's first jump is a one-block
 * gap along a one-wide walkway (floor x 0-4, gap x 5, landing x 6-8, all at z 0). Live, the take-off driver searched
 * with the look snapping, picked a diagonal run-up with a 90° turn in the air, and the capped look flew it off the
 * side of the walkway (presto-v4-trace1, 6 of 16 laps fell at x 7.1 z 1.30).
 */
public class TakeoffSlewTest {

    /** Floor tops at y 0 under the listed x ranges (inclusive pairs), z cell 0 only: a one-wide walkway. */
    static PlayerSim.World walkway(int... ranges) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                if (PlayerSim.floor(minZ) > 0 || PlayerSim.floor(maxZ) < 0 || PlayerSim.floor(minY) > -1 || PlayerSim.floor(maxY) < -1) {
                    return;
                }
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int k = 0; k < ranges.length; k += 2) {
                        if (bx >= ranges[k] && bx <= ranges[k + 1]) {
                            out.add(new double[]{bx, -1, 0, bx + 1, 0, 1});
                        }
                    }
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    /**
     * The Presto live course (PrestoLiveTest) with its floor top at y 0: one-wide walkway at z 0, the raised parts one
     * up, and the ceiling over x 36-48 three blocks above the floor (one row wider than the walkway on each side).
     */
    public static PlayerSim.World course() {
        return course(true);
    }

    public static PlayerSim.World course(boolean ceiling) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                        for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++) {
                            boolean raised = bx >= 21 && bx <= 24 || bx >= 28 && bx <= 31;
                            boolean floor = bx <= 4 || bx >= 6 && bx <= 8 || bx >= 11 && bx <= 13 || bx >= 17 && bx <= 20
                                    || bx >= 32 && bx <= 40 || bx >= 44 && bx <= 48 || bx >= 53 && bx <= 58;
                            boolean solid = by == -4 || bz == 0 && (floor && by >= -1 && by <= -1 || raised && by >= -1 && by <= 0)
                                    || ceiling && by == 3 && bx >= 36 && bx <= 48 && Math.abs(bz) <= 1;
                            if (solid) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                        }
                    }
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    /** The search TakeoffController sets up for a plain jump along +x from cell {@code src} to {@code dest}. */
    static JumpSearch takeoffSearch(PlayerSim.World w, int src, int dest) {
        JumpSearch js = new JumpSearch(w);
        js.dirX = 1;
        js.dirZ = 0;
        js.edge = src + 1;
        js.destX = dest;
        js.destY = 0;
        js.destZ = 0;
        js.side = 1;
        js.plan[0] = 1;
        js.plan[3] = JumpSearch.O1_STRAIGHT;
        return js;
    }

    static PlayerSim standing(PlayerSim.World w, double x, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = 0;
        s.z = z;
        s.onGround = true;
        return s;
    }

    /** Largest |z - 0.5| the body reaches flying plan {@code p} from {@code start}, with the look capped or not. */
    static double maxLateral(JumpSearch js, PlayerSim start, int[] p, boolean slew, float face) {
        boolean was = js.slewYaw;
        float wasFace = js.faceYaw;
        js.slewYaw = slew;
        js.faceYaw = face;
        double[] worst = {0};
        js.run(start, p, false, 0, (x, y, z) -> worst[0] = Math.max(worst[0], Math.abs(z - 0.5)));
        js.slewYaw = was;
        js.faceYaw = wasFace;
        return worst[0];
    }

    static String plan(int[] p) {
        StringBuilder sb = new StringBuilder();
        for (int k : p) sb.append(k);
        return sb.toString();
    }

    /**
     * The defect as flown live: from the run-up spot one block back, the snap search's pick (run-up 30° off the line,
     * a 90° turn at take-off) lands with the look snapping, and goes off the side of the walkway with the look capped.
     */
    @Test
    public void snapPlanForTheOneGapFallsOffUnderTheCap() {
        PlayerSim.World w = course();
        PlayerSim start = standing(w, 3.5, 0.5);
        JumpSearch snap = takeoffSearch(w, 4, 6);
        assertTrue("snap search finds a plan", snap.search(start, false));
        int[] p = snap.plan.clone();
        assertTrue("the snap plan lands when the look snaps", snap.run(start, p, false, 0, null));
        assertNotEquals("the snap pick turns", JumpSearch.O1_STRAIGHT, p[3]);
        JumpSearch capped = takeoffSearch(w, 4, 6);
        capped.slewYaw = true;
        capped.faceYaw = -90f;
        assertFalse("flown through the 32° look it does not land", capped.run(start, p, false, 0, null));
        double lat = maxLateral(capped, start, p, true, -90f);
        System.out.println("TAKEOFF-SLEW snap plan=" + plan(p) + " max lateral capped=" + String.format("%.3f", lat));
        assertTrue("it leaves the one-wide walkway sideways, |z-0.5| " + lat, lat > 0.8);
    }

    /**
     * The fix's contract. A capped search only hands out plans the capped look lands; the takeoff then takes one only
     * if it settles with the centre over the landing block from the start and from 5 cm to either side. One block back
     * (where the walk-back stands, and where the live laps fell) nothing passes, so the jump is handed back to the
     * movement; from the take-off block's centre a plan does, so the check is not simply "never".
     */
    @Test
    public void takeoffOnlyTakesPlansThatSurviveTheCapAndASmallShift() {
        PlayerSim.World w = course();
        for (double x : new double[]{3.5, 3.8, 4.0, 4.2, 4.5}) {
            for (double z : new double[]{0.4, 0.5, 0.62}) {
                PlayerSim start = standing(w, x, z);
                JumpSearch js = takeoffSearch(w, 4, 6);
                js.slewYaw = true;
                js.faceYaw = -90f;
                if (!js.search(start, false)) {
                    continue;
                }
                int[] p = js.plan.clone();
                assertTrue("capped plan " + plan(p) + " from " + x + "," + z + " lands capped", js.run(start, p, false, 0, null));
                boolean take = TakeoffController.takeable(js, start);
                PlayerSim end = js.sim();
                System.out.println("TAKEOFF-SLEW from " + x + "," + z + " plan " + plan(p) + " take=" + take + " settles "
                        + String.format("%.3f,%.3f", end.x, end.z) + " max |z-0.5| in flight " + String.format("%.3f", maxLateral(js, start, p, true, -90f)));
                if (take) {
                    assertTrue("taken plan settles with the centre on the walkway, z " + end.z, end.onGround && Math.abs(end.z - 0.5) <= 0.5);
                }
            }
        }
        for (double z : new double[]{0.45, 0.5, 0.55, 0.62}) {
            JumpSearch back = takeoffSearch(w, 4, 6);
            back.slewYaw = true;
            back.faceYaw = -90f;
            PlayerSim s = standing(w, 3.5, z);
            assertFalse("one block back at z " + z + ": nothing the takeoff takes, hand back",
                    back.search(s, false) && TakeoffController.takeable(back, s));
        }
        JumpSearch centre = takeoffSearch(w, 4, 6);
        centre.slewYaw = true;
        centre.faceYaw = -90f;
        PlayerSim c = standing(w, 4.5, 0.5);
        assertTrue("from the take-off block's centre a plan is taken", centre.search(c, false) && TakeoffController.takeable(centre, c));
    }

    /**
     * The 4-gap at x 48-53 under the ceiling's end. From the state the live walk-back stopped in (presto-v4-trace1 lap
     * 2, and laps 4, 5, 8 within a few mm) a capped plan exists; half a decimetre further back nothing lands,
     * ceiling or not: the sprint's ticks fall differently against the edge. The run-up spot decides the jump.
     */
    @Test
    public void fourGapRunUpSpotDecidesTheJump() {
        PlayerSim.World w = course();
        PlayerSim live = standing(w, 47.452, 0.621);
        live.vx = -0.0112;
        live.vz = 0.0073;
        JumpSearch js = takeoffSearch(w, 48, 53);
        js.slewYaw = true;
        js.faceYaw = -90f;
        assertTrue("capped plan from the live walk-back state", js.search(live, false));
        assertTrue(js.run(live, js.plan.clone(), false, 0, null));
        JumpSearch far = takeoffSearch(w, 48, 53);
        far.slewYaw = true;
        far.faceYaw = -90f;
        assertFalse("from the run-up spot's centre nothing lands", far.search(standing(w, 47.5, 0.5), false));
        JumpSearch open = takeoffSearch(course(false), 48, 53);
        open.slewYaw = true;
        open.faceYaw = -90f;
        assertFalse("nor without the ceiling: it is the run-up's phase, not the ceiling",
                open.search(standing(course(false), 47.5, 0.5), false));
    }
}
