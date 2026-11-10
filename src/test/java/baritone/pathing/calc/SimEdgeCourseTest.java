/*
 * Ostinato 2.0 Phase 05: every leg of the live A/B course (SimEdgeCourse) plans with legacy A* and with simulated edges, headless.
 */
package baritone.pathing.calc;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.legato.LegatoCourse;
import baritone.pathing.legato.SimEdgeCourse;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Locale;

import static baritone.pathing.calc.MaestroTest.Y;
import static baritone.pathing.calc.MaestroTest.floor;
import static baritone.pathing.calc.MaestroTest.quiet;
import static org.junit.Assert.*;

public class SimEdgeCourseTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    /** The course with its floor at Y - 1 (course y F + 1 is synthetic Y). */
    static SyntheticWorld course() {
        SyntheticWorld w = floor(SimEdgeCourse.SIZE + 1, SimEdgeCourse.SIZE + 1);
        for (int x = 0; x <= SimEdgeCourse.SIZE; x++) {
            for (int z = 0; z <= SimEdgeCourse.SIZE; z++) {
                for (int y = SimEdgeCourse.F + 1; y <= SimEdgeCourse.F + 5; y++) {
                    if (SimEdgeCourse.solid(x, y, z)) {
                        w.set(x, Y + y - SimEdgeCourse.F - 1, z, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        return w;
    }

    @Test
    public void startsAndGoalsAreOpen() {
        for (LegatoCourse.Leg leg : SimEdgeCourse.LEGS) {
            assertEquals(leg.name(), 0, SimEdgeCourse.height(leg.sx(), leg.sz()));
            assertEquals(leg.name(), 0, SimEdgeCourse.height(leg.gx(), leg.gz()));
            assertTrue(SimEdgeCourse.solid(leg.sx(), SimEdgeCourse.F, leg.sz()));
            assertFalse(SimEdgeCourse.solid(leg.sx(), SimEdgeCourse.F + 1, leg.sz()));
        }
    }

    @Test
    public void everyLegPlansBothWays() {
        SyntheticWorld w = course();
        for (LegatoCourse.Leg leg : SimEdgeCourse.LEGS) {
            BetterBlockPos s = new BetterBlockPos(leg.sx(), Y, leg.sz());
            GoalBlock g = new GoalBlock(leg.gx(), Y, leg.gz());
            Path a = (Path) quiet(PathBench.ASTAR.create(s, g, PathBench.context(w))).orElseThrow();
            SimEdgePathFinder f = SimEdgeTest.finder(w, s, g);
            Path b = (Path) quiet(f).orElseThrow();
            assertTrue(leg.name(), g.isInGoal(a.getDest()));
            assertTrue(leg.name(), g.isInGoal(b.getDest()));
            assertFalse(leg.name(), f.fellBack());
            assertTrue(leg.name(), f.simulatedEdges() > 0);
            SimEdge e = SimEdgeTest.edge(w);
            double pa = SimEdgeTest.sum(e.price(SimEdgeTest.cells(a), null)), pb = SimEdgeTest.sum(e.price(SimEdgeTest.cells(b), null));
            System.out.printf(Locale.ROOT, "SIMEDGE course %s: A* %d pos sim-priced %.2f | sim-edge %d pos sim-priced %.2f (%.4f) %d simulated %d legacy%n",
                    leg.name(), a.positions().size(), pa, b.positions().size(), pb, pb / pa, f.simulatedEdges(), f.legacyEdges());
        }
    }
}
