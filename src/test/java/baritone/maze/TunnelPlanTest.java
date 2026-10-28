package baritone.maze;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Ostinato: #maze.tunnels layout grows ramps (run77 dug 30 straight edges and never changed level). */
public class TunnelPlanTest {

    @Test
    public void growsRampsWithinSixtyEdges() {
        for (long seed = 0; seed < 5; seed++) {
            TunnelPlan plan = new TunnelPlan(seed, 0, 64, 0);
            plan.block(new TunnelPlan.Node(-1, 0, 0));
            plan.markDug(new TunnelPlan.Node(0, 0, 0), null, 0);
            BlockPos near = plan.center(new TunnelPlan.Node(0, 0, 0));
            int ramps = 0;
            for (int i = 0; i < 60; i++) {
                TunnelPlan.Edge e = plan.next(near);
                if (e == null) break;
                if (e.from().gy() != e.to().gy()) ramps++;
                plan.markDug(e.to(), e.from(), e.depth());
                near = plan.center(e.to());
            }
            assertTrue("seed " + seed + " grew no ramp in 60 edges", ramps >= 1);
        }
    }
}
