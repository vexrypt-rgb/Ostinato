/*
 * Ostinato Legato: the any-angle (Theta*) search on flat walking moves, and its fallback to plain A*.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.legato.LegatoFailures;
import baritone.pathing.legato.LegatoFeasibility;
import baritone.pathing.legato.LegatoSmoother;
import baritone.pathing.legato.LegatoStats;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class LegatoSearchTest {

    static final int Y = 64;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void offAgain() {
        Baritone.settings().legato.value = false;
        Baritone.settings().legatoSearch.value = false;
        Baritone.settings().legatoSmoothing.value = true;
        LegatoFailures.clear();
    }

    static SyntheticWorld floor(int sx, int sz) {
        SyntheticWorld w = new SyntheticWorld(sx, sz);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                w.set(x, 0, z, Blocks.BEDROCK.defaultBlockState());
                for (int y = 1; y < Y; y++) {
                    w.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        return w;
    }

    static void wall(SyntheticWorld w, int x, int z) {
        w.set(x, Y, z, Blocks.STONE.defaultBlockState());
        w.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
    }

    static Path search(SyntheticWorld w, BetterBlockPos start, Goal goal, boolean theta) {
        Baritone.settings().legato.value = theta;
        Baritone.settings().legatoSearch.value = theta;
        CalculationContext ctx = PathBench.context(w);
        assertEquals(theta, ctx.legatoSearch);
        AStarPathFinder f = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, ctx), ctx);
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream()));
        Optional<IPath> p;
        try {
            p = f.calculate0(10_000, 10_000);
        } finally {
            System.setOut(out);
        }
        assertTrue(p.isPresent());
        assertTrue(goal.isInGoal(p.get().getDest()));
        return (Path) p.get();
    }

    /** Every step is a grid move (at most a knight move) or a line the evaluator passes. */
    static void allStepsWalkable(SyntheticWorld w, Path p) {
        CalculationContext ctx = PathBench.context(w);
        List<BetterBlockPos> pos = p.positions();
        LegatoFeasibility.Result r = new LegatoFeasibility.Result();
        for (int i = 0; i + 1 < pos.size(); i++) {
            BetterBlockPos a = pos.get(i), b = pos.get(i + 1);
            int dx = Math.abs(b.x - a.x), dz = Math.abs(b.z - a.z);
            if (dx <= 2 && dz <= 2 && dx + dz <= 3) {
                continue;
            }
            assertEquals("line " + a + " -> " + b, a.y, b.y);
            assertTrue("line " + a + " -> " + b + ": " + LegatoFeasibility.evaluate(ctx, a.x, a.y, a.z, b.x, b.z, r).reason,
                    LegatoFeasibility.evaluate(ctx, a.x, a.y, a.z, b.x, b.z, r).feasible());
        }
    }

    static double length(Path p) {
        double s = 0;
        List<BetterBlockPos> pos = p.positions();
        for (int i = 0; i + 1 < pos.size(); i++) {
            s += Math.hypot(pos.get(i + 1).x - pos.get(i).x, pos.get(i + 1).z - pos.get(i).z);
        }
        return s;
    }

    @Test
    public void openGroundFindsShorterStraighterRoute() {
        SyntheticWorld w = floor(64, 48);
        BetterBlockPos start = new BetterBlockPos(5, Y, 5);
        GoalBlock goal = new GoalBlock(50, Y, 22); // not a 45 degree target
        Path grid = search(w, start, goal, false);
        long checks0 = LegatoStats.thetaChecks();
        Path any = search(w, start, goal, true);
        assertTrue(LegatoStats.thetaChecks() > checks0);
        allStepsWalkable(w, any);
        assertTrue("theta " + any.totalCost() + " grid " + grid.totalCost(), any.totalCost() <= grid.totalCost() + 1e-6);
        assertTrue(any.positions().size() < grid.positions().size());
        assertTrue(length(any) <= length(grid) + 1e-6);
        double straight = Math.hypot(45, 17);
        assertTrue("length " + length(any) + " vs straight " + straight, length(any) < straight * 1.02);
    }

    @Test
    public void obstaclesAreRespected() {
        SyntheticWorld w = floor(64, 48);
        for (int z = 0; z < 30; z++) {
            wall(w, 25, z); // a wall with a gap past z 30
        }
        for (int i = 0; i < 40; i += 3) {
            wall(w, 36 + (i % 7), 4 + i % 37); // a scatter of pillars
        }
        BetterBlockPos start = new BetterBlockPos(5, Y, 8);
        GoalBlock goal = new GoalBlock(55, Y, 12);
        Path grid = search(w, start, goal, false);
        Path any = search(w, start, goal, true);
        allStepsWalkable(w, any);
        assertTrue(any.totalCost() <= grid.totalCost() + 1e-6);
    }

    @Test
    public void nothingButGridMovesOnUnsupportedGround() {
        // ice everywhere: no line is supported, the search is plain A*
        SyntheticWorld w = floor(40, 40);
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) {
                w.set(x, Y - 1, z, Blocks.PACKED_ICE.defaultBlockState());
            }
        }
        BetterBlockPos start = new BetterBlockPos(3, Y, 3);
        GoalBlock goal = new GoalBlock(30, Y, 14);
        Path grid = search(w, start, goal, false);
        Path any = search(w, start, goal, true);
        assertEquals(grid.positions(), any.positions());
        assertEquals(grid.totalCost(), any.totalCost(), 1e-9);
    }

    @Test
    public void offIsUnchanged() {
        SyntheticWorld w = floor(64, 48);
        BetterBlockPos start = new BetterBlockPos(5, Y, 5);
        GoalBlock goal = new GoalBlock(50, Y, 22);
        long checks0 = LegatoStats.thetaChecks();
        Path a = search(w, start, goal, false);
        Path b = search(w, start, goal, false);
        assertEquals(checks0, LegatoStats.thetaChecks());
        assertEquals(a.positions(), b.positions());
        // smoothing alone does not change the search either
        Baritone.settings().legato.value = true;
        Baritone.settings().legatoSearch.value = false;
        CalculationContext ctx = PathBench.context(w);
        assertTrue(ctx.legatoSmooth);
        assertFalse(ctx.legatoSearch);
    }

    @Test
    public void searchCostIsBounded() {
        // the Theta* check must not blow up the search: count nodes and time both ways on the bench's flat terrain
        SyntheticWorld w = floor(200, 64);
        BetterBlockPos start = new BetterBlockPos(4, Y, 6);
        GoalBlock goal = new GoalBlock(190, Y, 51);
        for (int i = 0; i < 2; i++) { // warm
            search(w, start, goal, false);
            search(w, start, goal, true);
        }
        long t0 = System.nanoTime();
        Path grid = search(w, start, goal, false);
        long t1 = System.nanoTime();
        long c0 = LegatoStats.thetaChecks(), a0 = LegatoStats.thetaAccepts();
        Path any = search(w, start, goal, true);
        long t2 = System.nanoTime();
        System.out.println("LEGATO theta checks " + (LegatoStats.thetaChecks() - c0) + " accepts " + (LegatoStats.thetaAccepts() - a0)
                + " positions " + grid.positions().size() + " -> " + any.positions().size() + " any=" + any.positions());
        System.out.printf(java.util.Locale.ROOT, "LEGATO search bench: grid %.1f ms %d nodes cost %.1f len %.1f | theta %.1f ms %d nodes cost %.1f len %.1f%n",
                (t1 - t0) / 1e6, grid.getNumNodesConsidered(), grid.totalCost(), length(grid),
                (t2 - t1) / 1e6, any.getNumNodesConsidered(), any.totalCost(), length(any));
        assertTrue(any.getNumNodesConsidered() <= grid.getNumNodesConsidered() * 3 + 100);
        allStepsWalkable(w, any);
    }

    /** Runs the shortcut pass over a found route's positions, with the real line check (headless: no movements). */
    static LegatoSmoother.Plan smoothed(SyntheticWorld w, Path p) {
        CalculationContext ctx = PathBench.context(w);
        List<BetterBlockPos> pos = p.positions();
        double per = LegatoFeasibility.perBlock(ctx);
        LegatoFeasibility.Result r = new LegatoFeasibility.Result();
        return LegatoSmoother.smooth(new LegatoSmoother.Route() {
            public int moves() { return pos.size() - 1; }
            public int x(int i) { return pos.get(i).x; }
            public int y(int i) { return pos.get(i).y; }
            public int z(int i) { return pos.get(i).z; }
            public boolean eligible(int m) { return pos.get(m).y == pos.get(m + 1).y; }
            public double cost(int m) { return Math.hypot(pos.get(m + 1).x - pos.get(m).x, pos.get(m + 1).z - pos.get(m).z) * per; }
        }, (x0, y, z0, x1, z1) -> LegatoFeasibility.evaluate(ctx, x0, y, z0, x1, z1, r).cost);
    }

    static double length(LegatoSmoother.Plan p) {
        double s = 0;
        for (int k = 0; k + 1 < p.size; k++) {
            s += Math.hypot(p.x[k + 1] - p.x[k], p.z[k + 1] - p.z[k]);
        }
        return s;
    }

    @Test
    public void smoothingStraightensTheBenchRoute() {
        SyntheticWorld w = floor(200, 64);
        BetterBlockPos start = new BetterBlockPos(4, Y, 6);
        GoalBlock goal = new GoalBlock(190, Y, 51);
        Path grid = search(w, start, goal, false);
        smoothed(w, grid); // warm
        long t0 = System.nanoTime();
        LegatoSmoother.Plan plan = smoothed(w, grid);
        long t1 = System.nanoTime();
        double straight = Math.hypot(186, 45);
        System.out.printf(java.util.Locale.ROOT, "LEGATO smooth bench: %.2f ms, %d evals, positions %d -> %d, len %.1f -> %.1f (straight %.1f)%n",
                (t1 - t0) / 1e6, plan.evaluations, grid.positions().size(), plan.size, length(grid), length(plan), straight);
        assertFalse(plan.unchanged());
        assertTrue(length(plan) <= length(grid));
        assertTrue("len " + length(plan), length(plan) < straight * 1.01);
        assertTrue(plan.evaluations <= LegatoSmoother.MAX_EVALUATIONS);
    }

    @Test
    public void smoothingRespectsObstacles() {
        SyntheticWorld w = floor(64, 48);
        for (int z = 0; z < 30; z++) {
            wall(w, 25, z);
        }
        BetterBlockPos start = new BetterBlockPos(5, Y, 8);
        GoalBlock goal = new GoalBlock(55, Y, 12);
        Path grid = search(w, start, goal, false);
        LegatoSmoother.Plan plan = smoothed(w, grid);
        CalculationContext ctx = PathBench.context(w);
        LegatoFeasibility.Result r = new LegatoFeasibility.Result();
        for (int k = 0; k + 1 < plan.size; k++) {
            if (plan.move[k] < 0) {
                assertTrue(LegatoFeasibility.evaluate(ctx, plan.x[k], plan.y[k], plan.z[k], plan.x[k + 1], plan.z[k + 1], r).feasible());
            }
        }
        assertTrue(length(plan) <= length(grid));
    }
}
