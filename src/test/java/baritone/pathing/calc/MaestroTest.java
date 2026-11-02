/*
 * Ostinato Maestro: the action-aware planner (heading states, turn charge, budgets, cancellation, fallback to A*).
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
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

public class MaestroTest {

    static final int Y = 64;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void restore() {
        Baritone.settings().maestroTurnCost.value = 1.0;
        Baritone.settings().maestroMaxStates.value = 1_500_000;
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

    static MaestroPathFinder maestro(SyntheticWorld w, BetterBlockPos s, Goal g) {
        CalculationContext c = PathBench.context(w);
        return new MaestroPathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c, 1);
    }

    static Optional<IPath> quiet(AbstractNodeCostSearch f) {
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream()));
        try {
            return PathBench.plan(f);
        } finally {
            System.setOut(out);
        }
    }

    /** Sum of turn charges along a path's positions (what Maestro adds on top of the move costs). */
    static double turns(IPath p, double turnCost) {
        List<BetterBlockPos> ps = p.positions();
        int h = MaestroPathFinder.NONE;
        double t = 0;
        for (int i = 1; i < ps.size(); i++) {
            int n = MaestroPathFinder.sector(ps.get(i).x - ps.get(i - 1).x, ps.get(i).z - ps.get(i - 1).z);
            t += MaestroPathFinder.turn(h, n, turnCost);
            h = n;
        }
        return t;
    }

    /**
     * OSTINATO_MAESTRO_BENCH=1: A* and Maestro over the PathBench queries (all terrains, all lengths), same world and
     * timeouts; writes build/pathbench/maestro.md with failures, plan time, nodes, move cost and turn charge per arm.
     */
    @Test
    public void benchmark() {
        org.junit.Assume.assumeTrue(System.getenv("OSTINATO_MAESTRO_BENCH") != null);
        int reps = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_BENCH_REPS", "2"));
        // OSTINATO_MAESTRO_BENCH_TERRAINS=HILLS,WATER limits the run to those terrains
        baritone.pathing.calc.bench.Terrain[] terrains = java.util.Arrays.stream(baritone.pathing.calc.bench.Terrain.values())
                .filter(t -> System.getenv("OSTINATO_MAESTRO_BENCH_TERRAINS") == null
                        || java.util.Arrays.asList(System.getenv("OSTINATO_MAESTRO_BENCH_TERRAINS").split(",")).contains(t.name()))
                .toArray(baritone.pathing.calc.bench.Terrain[]::new);
        List<PathBench.Query> qs = PathBench.queries(terrains);
        StringBuilder sb = new StringBuilder("# Maestro headless benchmark\n\n");
        sb.append(PathBench.report("A*", PathBench.bench(qs, PathBench.ASTAR, reps), reps));
        sb.append(PathBench.report("Maestro (turn cost 1.0)", PathBench.bench(qs, PathBench.finder("maestro"), reps), reps));
        // turn charge of each arm's paths (one planning pass each), paired per query
        sb.append("| terrain | queries both ok | A* turn charge (mean) | Maestro turn charge (mean) | A* move cost (mean) | Maestro move cost (mean) |\n|---|---|---|---|---|---|\n");
        for (baritone.pathing.calc.bench.Terrain t : terrains) {
            double ta = 0, tm = 0, ca = 0, cm = 0;
            int n = 0;
            for (PathBench.Query q : qs) {
                if (q.terrain() != t) continue;
                CalculationContext c = PathBench.context(PathBench.world(t));
                Optional<IPath> a = quiet(PathBench.ASTAR.create(q.start(), q.goal(), c));
                Optional<IPath> m = quiet(PathBench.finder("maestro").create(q.start(), q.goal(), PathBench.context(PathBench.world(t))));
                if (a.isEmpty() || m.isEmpty() || !q.goal().isInGoal(a.get().getDest()) || !q.goal().isInGoal(m.get().getDest())) continue;
                n++;
                ta += turns(a.get(), 1);
                tm += turns(m.get(), 1);
                ca += ((Path) a.get()).totalCost();
                cm += ((Path) m.get()).totalCost();
            }
            sb.append(String.format(java.util.Locale.ROOT, "| %s | %d | %.1f | %.1f | %.1f | %.1f |%n", t, n, ta / Math.max(1, n), tm / Math.max(1, n), ca / Math.max(1, n), cm / Math.max(1, n)));
        }
        sb.append(String.format(java.util.Locale.ROOT, "%nMaestro: %d searches, %d fallbacks, %d capped, %d exceptions, %d states created, %d pruned.%n",
                MaestroPathFinder.searches.get(), MaestroPathFinder.fallbacks.get(), MaestroPathFinder.capped.get(), MaestroPathFinder.exceptions.get(),
                MaestroPathFinder.statesCreated.get(), MaestroPathFinder.statesPruned.get()));
        PathBench.write("maestro.md", sb.toString());
    }

    @Test
    public void settingsDefaultOff() {
        assertFalse(Baritone.settings().maestro.value);
        assertFalse(Baritone.settings().maestro.defaultValue);
    }

    @Test
    public void sectorsAndTurns() {
        assertEquals(MaestroPathFinder.NONE, MaestroPathFinder.sector(0, 0));
        assertEquals(0, MaestroPathFinder.sector(1, 0));
        assertEquals(4, MaestroPathFinder.sector(0, 1));
        assertEquals(8, MaestroPathFinder.sector(-1, 0));
        assertEquals(12, MaestroPathFinder.sector(0, -1));
        assertEquals(2, MaestroPathFinder.sector(1, 1));
        assertEquals(0, MaestroPathFinder.turn(0, 0, 1), 0);
        assertEquals(0, MaestroPathFinder.turn(MaestroPathFinder.NONE, 4, 1), 0);
        assertEquals(1.5, MaestroPathFinder.turn(0, 4, 1), 1e-9); // right angle
        assertEquals(6, MaestroPathFinder.turn(0, 8, 1), 1e-9); // reversal
        assertEquals(MaestroPathFinder.turn(1, 15, 1), MaestroPathFinder.turn(15, 1, 1), 0); // symmetric, wraps
        assertEquals(0, MaestroPathFinder.turn(0, 8, 0), 0);
    }

    /** With no turn charge the state space adds nothing: same optimal cost as A*. */
    @Test
    public void zeroTurnCostMatchesAStar() {
        Baritone.settings().maestroTurnCost.value = 0.0;
        SyntheticWorld w = floor(40, 40);
        for (int z = 0; z < 30; z++) w.set(20, Y, z, Blocks.STONE.defaultBlockState());
        for (int z = 0; z < 30; z++) w.set(20, Y + 1, z, Blocks.STONE.defaultBlockState());
        BetterBlockPos s = new BetterBlockPos(5, Y, 5);
        Goal g = new GoalBlock(35, Y, 8);
        Path a = (Path) quiet(PathBench.ASTAR.create(s, g, PathBench.context(w))).orElseThrow();
        Path m = (Path) quiet(maestro(w, s, g)).orElseThrow();
        assertTrue(g.isInGoal(m.getDest()));
        assertEquals(a.totalCost(), m.totalCost(), 1e-6);
    }

    /**
     * On open ground many routes tie on move cost; Maestro minimises moves + turn charge, so its path never scores worse
     * than A*'s on that objective.
     */
    @Test
    public void fewerTurnsAtTheSameObjective() {
        SyntheticWorld w = floor(48, 48);
        // a field of pillars that makes zig-zags tempting for a heading-blind search
        for (int x = 8; x < 40; x += 4) for (int z = 6; z < 40; z += 5) {
            w.set(x, Y, z, Blocks.STONE.defaultBlockState());
            w.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
        }
        BetterBlockPos s = new BetterBlockPos(3, Y, 3);
        Goal g = new GoalBlock(44, Y, 37);
        // exact optimality on both sides: no tie-break nudge on the heuristic weight
        double tie = Baritone.settings().pathTieBreak.value;
        Baritone.settings().pathTieBreak.value = 0.0;
        Path a, m;
        try {
            a = (Path) quiet(PathBench.ASTAR.create(s, g, PathBench.context(w))).orElseThrow();
            m = (Path) quiet(maestro(w, s, g)).orElseThrow();
        } finally {
            Baritone.settings().pathTieBreak.value = tie;
        }
        assertTrue(g.isInGoal(m.getDest()));
        double objA = a.totalCost() + turns(a, 1), objM = m.totalCost() + turns(m, 1);
        assertTrue("maestro " + objM + " vs astar " + objA, objM <= objA + 1e-6);
        // (A*'s own path need not be move-cost optimal here: on this field Maestro found one cheaper on both counts)
    }

    @Test
    public void pruningKeepsTheObjectiveWithFewerStates() {
        SyntheticWorld w = floor(48, 48);
        for (int x = 8; x < 40; x += 4) for (int z = 6; z < 40; z += 5) {
            w.set(x, Y, z, Blocks.STONE.defaultBlockState());
            w.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
        }
        BetterBlockPos s = new BetterBlockPos(3, Y, 3);
        Goal g = new GoalBlock(44, Y, 37);
        double tie = Baritone.settings().pathTieBreak.value;
        Baritone.settings().pathTieBreak.value = 0.0;
        try {
            MaestroPathFinder.prune = false;
            MaestroPathFinder full = (MaestroPathFinder) maestro(w, s, g);
            Path pf = (Path) quiet(full).orElseThrow();
            MaestroPathFinder.prune = true;
            MaestroPathFinder pruned = (MaestroPathFinder) maestro(w, s, g);
            Path pp = (Path) quiet(pruned).orElseThrow();
            assertEquals(pf.totalCost() + turns(pf, 1), pp.totalCost() + turns(pp, 1), 1e-6);
            assertTrue(pruned.stateCount + " vs " + full.stateCount, pruned.stateCount < full.stateCount);
        } finally {
            MaestroPathFinder.prune = true;
            Baritone.settings().pathTieBreak.value = tie;
        }
    }

    @Test
    public void cappedSearchFallsBackToAStar() {
        Baritone.settings().maestroMaxStates.value = 3;
        SyntheticWorld w = floor(32, 32);
        BetterBlockPos s = new BetterBlockPos(3, Y, 3);
        Goal g = new GoalBlock(28, Y, 28);
        long fb = MaestroPathFinder.fallbacks.get(), cap = MaestroPathFinder.capped.get();
        Optional<IPath> p = quiet(maestro(w, s, g));
        assertTrue(MaestroPathFinder.capped.get() > cap);
        assertTrue(p.isPresent());
        // either a capped partial path, or (none far enough) the A* fallback reaching the goal
        if (MaestroPathFinder.fallbacks.get() > fb) {
            assertTrue(g.isInGoal(p.get().getDest()));
        }
    }

    @Test
    public void noRouteFallsBackAndFails() {
        SyntheticWorld w = floor(32, 32);
        // the goal is sealed in a stone box
        for (int x = 19; x <= 21; x++) for (int z = 19; z <= 21; z++) for (int y = Y; y <= Y + 2; y++)
            if (x != 20 || z != 20 || y == Y + 2) w.set(x, y, z, Blocks.BEDROCK.defaultBlockState());
        Baritone.settings().allowBreak.value = false;
        try {
            long fb = MaestroPathFinder.fallbacks.get();
            Optional<IPath> p = quiet(maestro(w, new BetterBlockPos(3, Y, 3), new GoalBlock(20, Y, 20)));
            assertFalse(p.isPresent() && new GoalBlock(20, Y, 20).isInGoal(p.get().getDest()));
            if (p.isEmpty()) {
                assertTrue(MaestroPathFinder.fallbacks.get() > fb);
            }
        } finally {
            Baritone.settings().allowBreak.value = true;
        }
    }

    @Test
    public void cancelledSearchReturnsNothing() {
        SyntheticWorld w = floor(32, 32);
        MaestroPathFinder f = maestro(w, new BetterBlockPos(3, Y, 3), new GoalBlock(28, Y, 28));
        f.cancel();
        assertTrue(quiet(f).isEmpty());
    }

    @Test
    public void paysCadenzaTerminalCosts() {
        SyntheticWorld w = floor(32, 32);
        BetterBlockPos s = new BetterBlockPos(5, Y, 5);
        // the near cell costs 50 ticks to finish from; the far one nothing
        Goal g = new baritone.api.pathing.goals.TerminalCostGoal() {
            public boolean isInGoal(int x, int y, int z) { return y == Y && z == 5 && (x == 7 || x == 12); }
            public double heuristic(int x, int y, int z) { return Math.min(GoalBlock.calculate(x - 7, y - Y, z - 5), GoalBlock.calculate(x - 12, y - Y, z - 5)); }
            public double terminalCost(int x, int y, int z) { return x == 7 ? 50 : 0; }
        };
        assertEquals(new BetterBlockPos(12, Y, 5), quiet(maestro(w, s, g)).orElseThrow().getDest());
    }
}
