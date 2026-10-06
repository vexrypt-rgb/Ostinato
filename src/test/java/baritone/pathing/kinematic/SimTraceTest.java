package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class SimTraceTest {

    private static PlayerSim.World floor(final float slip) {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                out.add(new double[]{-100, -1, -100, 100, 0, 100});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return slip;
            }
        };
    }

    /** Runs a sprint-jump on {@code truth} while the tracer predicts with {@code model}. */
    private static SimTrace fly(PlayerSim.World truth, PlayerSim.World model, float yawLag) {
        PlayerSim real = new PlayerSim(truth);
        real.x = 0.5;
        real.z = 0.5;
        real.onGround = true;
        SimTrace trace = new SimTrace("test", model);
        for (int t = 0; t < 30; t++) {
            boolean jump = t == 3;
            trace.observe(real, 0 + yawLag);
            trace.commit(real, 0, 1, true, jump);
            real.tick(0, 1, true, jump);
        }
        trace.observe(real, 0);
        return trace;
    }

    @Test
    public void aSimThatMatchesTheWorldHasNoError() {
        SimTrace t = fly(floor(0.6f), floor(0.6f), 0);
        assertEquals(0, t.maxStep(), 1e-9);
        assertEquals(0, t.openLoopError(), 1e-9);
        assertTrue(t.held());
        assertEquals(0, t.groundMismatches());
    }

    @Test
    public void wrongFrictionShowsUpAsStepAndDrift() {
        SimTrace t = fly(floor(0.6f), floor(0.8f), 0);
        assertTrue("step error " + t.maxStep(), t.maxStep() > 0.001);
        assertTrue("open loop drift " + t.openLoopError(), t.openLoopError() > 0.05);
    }

    @Test
    public void yawLagIsReported() {
        SimTrace t = fly(floor(0.6f), floor(0.6f), 7);
        assertEquals(7, t.maxYawLag(), 1e-6);
    }
}
