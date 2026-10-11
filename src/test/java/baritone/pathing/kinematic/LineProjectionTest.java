package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LineProjectionTest {

    private static final double WIDE = 1.1;

    /** Bastion run 121, moved to the origin: two falls south, then the way down turns west under where we started. */
    private static List<double[]> windingDescent() {
        double[][] cells = {{0, 78, 0}, {0, 75, 1}, {0, 73, 2}, {-1, 72, 2}, {-2, 71, 2}, {-3, 70, 2}, {-4, 69, 2}, {-5, 68, 2}};
        List<double[]> line = new ArrayList<>();
        double s = 0;
        for (double[] c : cells) {
            double x = c[0] + 0.5, z = c[2] + 0.5;
            if (!line.isEmpty()) {
                double[] p = line.get(line.size() - 1);
                s += Math.hypot(x - p[0], z - p[2]);
            }
            line.add(new double[]{x, c[1], z, s});
        }
        return line;
    }

    @Test
    public void onTheLineIsOnTheLine() {
        List<double[]> line = windingDescent();
        double[] start = KinematicController.project(line, 0.5, 78, 0.5, Double.MAX_VALUE);
        assertEquals(0, start[0], 1e-9);
        assertEquals(0, start[1], 1e-9);
        // half way down the first fall
        double[] falling = KinematicController.project(line, 0.5, 76.2, 1.2, Double.MAX_VALUE);
        assertEquals(0.7, falling[0], 1e-9);
        assertEquals(0, falling[1], 1e-9);
        // standing on the lower stretch, having walked there
        double[] lower = KinematicController.project(line, -2.5, 70, 2.5, Double.MAX_VALUE);
        assertEquals(5, lower[0], 1e-9);
        assertEquals(0, lower[1], 1e-9);
    }

    @Test
    public void theAirAboveALowerStretchIsNotOnIt() {
        List<double[]> line = windingDescent();
        // ran west off the top instead of taking the falls: six blocks above the stretch that passes underneath
        double[] off = KinematicController.project(line, -2.5, 77, 2.2, Double.MAX_VALUE);
        assertTrue("wide of every stretch at its own level: " + off[1], off[1] > WIDE);
        assertTrue("and not credited with the stretch below: " + off[0], off[0] <= 2);
    }

    @Test
    public void aRolloutCannotSkipAheadByFalling() {
        List<double[]> line = windingDescent();
        // the same run a second later, fallen to the level of the lower stretch; the rollout had got to s = 0.5
        double[] fell = KinematicController.project(line, -2.5, 70.5, 2.5, 0.5 + KinematicController.REACH_AHEAD);
        assertTrue("nothing within reach of where the rollout was: " + fell[1], fell[1] > WIDE);
    }

    @Test
    public void aJumpAlongTheLineStaysOnIt() {
        List<double[]> line = windingDescent();
        // apex of a jump (1.25 up) taken from the lower stretch, over the next cell down
        double[] apex = KinematicController.project(line, -3.5, 71.25, 2.5, Double.MAX_VALUE);
        assertEquals(6, apex[0], 1e-9);
        assertEquals(0, apex[1], 1e-9);
    }
}
