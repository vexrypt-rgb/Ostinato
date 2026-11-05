/*
 * Ostinato 2.0 Phase 05: simulated movement edges (SimEdge) and the search over them (SimEdgePathFinder).
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static baritone.pathing.calc.MaestroTest.Y;
import static baritone.pathing.calc.MaestroTest.floor;
import static baritone.pathing.calc.MaestroTest.quiet;
import static org.junit.Assert.*;

public class SimEdgeTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void restore() {
        Baritone.settings().simEdgeMaxStates.value = 1_500_000;
        Baritone.settings().simEdgeHorizon.value = 32;
        SimEdgePathFinder.prune = true;
    }

    static SimEdge edge(SyntheticWorld w) {
        CalculationContext c = PathBench.context(w);
        SimEdge e = new SimEdge(new BsiWorld(c.bsi), c);
        e.sprintAscends = true;
        return e;
    }

    static SimEdgePathFinder finder(SyntheticWorld w, BetterBlockPos s, Goal g) {
        CalculationContext c = PathBench.context(w);
        return new SimEdgePathFinder(s, s.x, s.y, s.z, g, new Favoring(null, c), c, 1);
    }

    static SimEdge.Body rest(int x, int y, int z) {
        SimEdge.Body b = new SimEdge.Body();
        b.known = true;
        b.x = x + 0.5;
        b.y = y;
        b.z = z + 0.5;
        b.onGround = true;
        b.yaw = -90; // facing +x
        return b;
    }

    /** At the source centre at steady sprint along +x (a known body, unlike the legacy-assumption unknown one). */
    static SimEdge.Body sprinting(int x, int y, int z) {
        SimEdge.Body b = rest(x, y, z);
        b.vx = SimEdge.SPRINT_SPEED;
        b.vy = SimEdge.STEADY_VY;
        b.sprinting = true;
        return b;
    }

    /** Chains straight traverses along +x from {@code b}; returns the ticks of each. */
    static int[] chainX(SimEdge e, SimEdge.Body b, int x0, int n) {
        int[] out = new int[n];
        SimEdge.Result r = new SimEdge.Result();
        SimEdge.Body cur = new SimEdge.Body().set(b);
        for (int i = 0; i < n; i++) {
            assertTrue("edge " + i, e.run(cur, SimEdge.TRAVERSE, x0 + i, Y, 5, x0 + i + 1, Y, 5, r));
            out[i] = r.ticks;
            cur.set(r.end);
        }
        return out;
    }

    @Test
    public void settingsDefaultOff() {
        assertFalse(Baritone.settings().simEdgeSearch.value);
        assertFalse(Baritone.settings().simEdgeSearch.defaultValue);
    }

    /** Steady PlayerSim sprint agrees with the legacy sprint traverse cost, which the heuristic is built on. */
    @Test
    public void steadySprintMatchesLegacyCost() {
        SimEdge e = edge(floor(64, 16));
        e.canSprint = true;
        int[] t = chainX(e, new SimEdge.Body().unknown(), 4, 40);
        int sum = 0;
        for (int i = 10; i < 40; i++) sum += t[i];
        assertEquals(30 * 3.5638, sum, 2.0);
        // from an unknown body (after a legacy edge) one edge is priced as one legacy block, centre to centre
        SimEdge.Result r = new SimEdge.Result();
        assertTrue(e.run(new SimEdge.Body().unknown(), SimEdge.TRAVERSE, 4, Y, 5, 5, Y, 5, r));
        assertEquals(3.5638, r.cost, 1.0);
    }

    /** From rest the first blocks cost more than the steady state the legacy cost assumes. */
    @Test
    public void accelerationFromRestIsCharged() {
        SimEdge e = edge(floor(64, 16));
        e.canSprint = true;
        int[] fromRest = chainX(e, rest(4, Y, 5), 4, 4);
        int[] steady = chainX(e, sprinting(4, Y, 5), 4, 4);
        // ground drag 0.546 a tick: the speed deficit sums to about 2.2 ticks of steady sprint
        assertTrue(sum(fromRest) + " vs " + sum(steady), sum(fromRest) >= sum(steady) + 2);
    }

    /** A right-angle turn at sprint costs more than going straight on, and a reversal more than that (two blocks each). */
    @Test
    public void turnsAreCharged() {
        SimEdge e = edge(floor(64, 32));
        e.canSprint = true;
        SimEdge.Result r = new SimEdge.Result();
        SimEdge.Body cur = new SimEdge.Body().unknown();
        for (int i = 0; i < 6; i++) { // six steady blocks along +x, ending in cell (10, 5)
            assertTrue(e.run(cur, SimEdge.TRAVERSE, 4 + i, Y, 5, 5 + i, Y, 5, r));
            cur.set(r.end);
        }
        int straight = two(e, cur, 10, 5, 1, 0), turn = two(e, cur, 10, 5, 0, 1), back = two(e, cur, 10, 5, -1, 0);
        assertTrue(turn + " vs " + straight, turn > straight);
        assertTrue(back + " vs " + turn, back > turn);
    }

    /** Ticks for two chained traverses from {@code b} at (x, z) in direction (dx, dz). */
    static int two(SimEdge e, SimEdge.Body b, int x, int z, int dx, int dz) {
        SimEdge.Result r = new SimEdge.Result();
        assertTrue(e.run(b, SimEdge.TRAVERSE, x, Y, z, x + dx, Y, z + dz, r));
        int t = r.ticks;
        SimEdge.Body next = new SimEdge.Body().set(r.end);
        assertTrue(e.run(next, SimEdge.TRAVERSE, x + dx, Y, z + dz, x + 2 * dx, Y, z + 2 * dz, r));
        return t + r.ticks;
    }

    /**
     * Chaining: every edge starts from the body the previous one ended with, so a chain of one-block edges takes
     * exactly as long as one uninterrupted simulation of the same run.
     */
    @Test
    public void chainedEdgesEqualOneContinuousRun() {
        SyntheticWorld w = floor(64, 16);
        SimEdge e = edge(w);
        e.canSprint = true;
        int[] t = chainX(e, rest(4, Y, 5), 4, 8);
        PlayerSim s = new PlayerSim(new BsiWorld(PathBench.context(w).bsi));
        s.x = 4.5;
        s.y = Y;
        s.z = 5.5;
        s.onGround = true;
        int ticks = 0;
        while (PlayerSim.floor(s.x) < 12) {
            s.tick(-90f, 1, 0, true, false);
            ticks++;
            assertTrue(ticks < 200);
        }
        assertEquals(ticks, sum(t));
    }

    @Test
    public void ascendWithHeadRoomIsSimulated() {
        SyntheticWorld w = floor(32, 16);
        w.set(10, Y, 5, Blocks.STONE.defaultBlockState()); // a step up at x = 10
        SimEdge e = edge(w);
        e.canSprint = true;
        assertTrue(e.eligible(SimEdge.ASCEND, 9, Y, 5, 10, Y + 1, 5));
        SimEdge.Result r = new SimEdge.Result();
        SimEdge.Body cur = new SimEdge.Body().unknown();
        for (int x = 4; x < 9; x++) {
            assertTrue(e.run(cur, SimEdge.TRAVERSE, x, Y, 5, x + 1, Y, 5, r));
            cur.set(r.end);
        }
        assertTrue(e.run(cur, SimEdge.ASCEND, 9, Y, 5, 10, Y + 1, 5, r));
        assertTrue(r.end.y >= Y + 1 - 0.01);
        assertTrue(r.air > 0);
        assertTrue("food " + r.food, r.food > 0);
        assertTrue("drift " + r.drift, r.drift > 0);
    }

    @Test
    public void ascendUnderALowCeilingIsNotSimulated() {
        SyntheticWorld w = floor(32, 16);
        w.set(10, Y, 5, Blocks.STONE.defaultBlockState());
        w.set(9, Y + 2, 5, Blocks.STONE.defaultBlockState()); // no head room over the source
        assertFalse(edge(w).eligible(SimEdge.ASCEND, 9, Y, 5, 10, Y + 1, 5));
    }

    @Test
    public void slotsSeparateMotionClasses() {
        SimEdge.Body b = new SimEdge.Body().unknown();
        assertEquals(SimEdge.UNKNOWN_SLOT, SimEdge.slot(b));
        b = rest(0, Y, 0);
        assertEquals(0, SimEdge.slot(b));
        b.vx = 0.153;
        int east = SimEdge.slot(b);
        b.vx = 0.118;
        int eastWalk = SimEdge.slot(b);
        b.vx = -0.153;
        int west = SimEdge.slot(b);
        assertNotEquals(east, eastWalk);
        assertNotEquals(east, west);
        b.onGround = false;
        assertNotEquals(west, SimEdge.slot(b));
        assertTrue(SimEdge.MOMENTUM_BOUND > 1 && SimEdge.MOMENTUM_BOUND < SimEdge.MAX_TICKS);
    }

    /** The search reaches the goal through a pillar field, with simulated edges, and prices its path consistently. */
    @Test
    public void searchReachesTheGoal() {
        SyntheticWorld w = pillars();
        BetterBlockPos s = new BetterBlockPos(3, Y, 3);
        Goal g = new GoalBlock(44, Y, 37);
        SimEdgePathFinder f = finder(w, s, g);
        Path p = (Path) quiet(f).orElseThrow();
        assertTrue(g.isInGoal(p.getDest()));
        assertFalse(f.fellBack());
        assertTrue(f.simulatedEdges() > 0);
        // the search's own cost at the goal is the chained sim price of the path it returned (unknown start)
        // (not exactly: a state's body can change after its children were priced from the old one; audit §6)
        double priced = sum(edge(w).price(cells(p), null)), own = f.costAt(44, Y, 37);
        System.out.printf(Locale.ROOT, "SIMEDGE goal: search %.2f, path re-priced %.2f, %d simulated, %d legacy, %d inconsistent%n",
                own, priced, f.simulatedEdges(), f.legacyEdges(), f.inconsistent());
        assertEquals(own, priced, 0.05 * own);
    }

    /** A start at rest is planned as one: it costs more than the same plan from the legacy steady state. */
    @Test
    public void startBodyIsUsed() {
        SyntheticWorld w = floor(48, 16);
        BetterBlockPos s = new BetterBlockPos(3, Y, 5);
        Goal g = new GoalBlock(30, Y, 5);
        SimEdgePathFinder moving = finder(w, s, g);
        moving.startBody(sprinting(3, Y, 5));
        quiet(moving).orElseThrow();
        SimEdgePathFinder still = finder(w, s, g);
        still.startBody(rest(3, Y, 5));
        quiet(still).orElseThrow();
        assertTrue(still.costAt(30, Y, 5) + " vs " + moving.costAt(30, Y, 5), still.costAt(30, Y, 5) >= moving.costAt(30, Y, 5) + 2);
    }

    /** Dominance pruning is approximate: it may cost at most the bound, and must save states. */
    @Test
    public void pruningKeepsTheObjective() {
        SyntheticWorld w = pillars();
        BetterBlockPos s = new BetterBlockPos(3, Y, 3);
        Goal g = new GoalBlock(44, Y, 37);
        double tie = Baritone.settings().pathTieBreak.value;
        Baritone.settings().pathTieBreak.value = 0.0;
        try {
            SimEdgePathFinder.prune = false;
            SimEdgePathFinder full = finder(w, s, g);
            quiet(full).orElseThrow();
            SimEdgePathFinder.prune = true;
            SimEdgePathFinder pruned = finder(w, s, g);
            quiet(pruned).orElseThrow();
            double a = full.costAt(44, Y, 37), b = pruned.costAt(44, Y, 37);
            System.out.printf(Locale.ROOT, "SIMEDGE prune: full %.2f (%d states) pruned %.2f (%d states)%n", a, full.stateCount, b, pruned.stateCount);
            assertTrue(b + " vs " + a, b <= a + SimEdge.MOMENTUM_BOUND);
            assertTrue(pruned.stateCount <= full.stateCount);
        } finally {
            SimEdgePathFinder.prune = true;
            Baritone.settings().pathTieBreak.value = tie;
        }
    }

    /** Past the horizon nothing is simulated: with horizon 1 almost every edge of a long route is legacy. */
    @Test
    public void horizonLimitsSimulation() {
        SyntheticWorld w = floor(64, 16);
        BetterBlockPos s = new BetterBlockPos(3, Y, 5);
        Goal g = new GoalBlock(60, Y, 5);
        Baritone.settings().simEdgeHorizon.value = 0;
        SimEdgePathFinder all = finder(w, s, g);
        quiet(all).orElseThrow();
        Baritone.settings().simEdgeHorizon.value = 1;
        SimEdgePathFinder near = finder(w, s, g);
        quiet(near).orElseThrow();
        assertTrue(near.simulatedEdges() + " vs " + all.simulatedEdges(), near.simulatedEdges() * 5 < all.simulatedEdges());
        assertTrue(near.stateCount <= all.stateCount);
    }

    @Test
    public void cappedSearchFallsBackToAStar() {
        Baritone.settings().simEdgeMaxStates.value = 3;
        SyntheticWorld w = floor(32, 32);
        BetterBlockPos s = new BetterBlockPos(3, Y, 3);
        Goal g = new GoalBlock(28, Y, 28);
        long cap = SimEdgePathFinder.capped.get();
        Optional<IPath> p = quiet(finder(w, s, g));
        assertTrue(SimEdgePathFinder.capped.get() > cap);
        assertTrue(p.isPresent());
    }

    @Test
    public void noRouteFails() {
        SyntheticWorld w = floor(32, 32);
        for (int x = 19; x <= 21; x++) for (int z = 19; z <= 21; z++) for (int y = Y; y <= Y + 2; y++)
            if (x != 20 || z != 20 || y == Y + 2) w.set(x, y, z, Blocks.BEDROCK.defaultBlockState());
        Baritone.settings().allowBreak.value = false;
        try {
            Optional<IPath> p = quiet(finder(w, new BetterBlockPos(3, Y, 3), new GoalBlock(20, Y, 20)));
            assertFalse(p.isPresent() && new GoalBlock(20, Y, 20).isInGoal(p.get().getDest()));
        } finally {
            Baritone.settings().allowBreak.value = true;
        }
    }

    @Test
    public void cancelledSearchReturnsNothing() {
        SimEdgePathFinder f = finder(floor(32, 32), new BetterBlockPos(3, Y, 3), new GoalBlock(28, Y, 28));
        f.cancel();
        assertTrue(quiet(f).isEmpty());
    }

    /**
     * OSTINATO_SIMEDGE_BENCH=1: A* and the sim-edge search over the PathBench queries. Per arm: failures, plan time,
     * nodes; per query both ok: each path priced by both models (legacy move costs, chained sim from the legacy steady
     * state), so route quality is compared under the sim model and the size of the model change is visible.
     * Writes build/pathbench/simedge.md.
     */
    @Test
    public void benchmark() {
        org.junit.Assume.assumeTrue(System.getenv("OSTINATO_SIMEDGE_BENCH") != null);
        int reps = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_BENCH_REPS", "2"));
        baritone.pathing.calc.bench.Terrain[] terrains = java.util.Arrays.stream(baritone.pathing.calc.bench.Terrain.values())
                .filter(t -> System.getenv("OSTINATO_SIMEDGE_BENCH_TERRAINS") == null
                        || java.util.Arrays.asList(System.getenv("OSTINATO_SIMEDGE_BENCH_TERRAINS").split(",")).contains(t.name()))
                .toArray(baritone.pathing.calc.bench.Terrain[]::new);
        List<PathBench.Query> qs = PathBench.queries(terrains);
        StringBuilder sb = new StringBuilder("# Sim-edge headless benchmark (Tier S)\n\n");
        sb.append(PathBench.report("A* (legacy)", PathBench.bench(qs, PathBench.ASTAR, reps), reps));
        long s0 = SimEdgePathFinder.sims.get(), e0 = SimEdgePathFinder.simEdges.get(), l0 = SimEdgePathFinder.legacy.get(),
                i0 = SimEdgePathFinder.inconsistencies.get(), f0 = SimEdgePathFinder.fallbacks.get(), c0 = SimEdgePathFinder.capped.get(),
                st0 = SimEdgePathFinder.statesCreated.get(), m0 = SimEdgePathFinder.simMisses.get();
        Baritone.settings().simEdgeHorizon.value = Integer.parseInt(System.getenv().getOrDefault("OSTINATO_SIMEDGE_HORIZON", "32"));
        sb.append(PathBench.report("Sim-edge (horizon " + Baritone.settings().simEdgeHorizon.value + ")", PathBench.bench(qs, PathBench.finder("simedge"), reps), reps));
        sb.append(String.format(Locale.ROOT, "Sim-edge totals: %d sims, %d simulated edges, %d legacy edges, %d sim misses (eligible, not reached), %d inconsistent edges, %d fallbacks, %d capped, %d states.%n%n",
                SimEdgePathFinder.sims.get() - s0, SimEdgePathFinder.simEdges.get() - e0, SimEdgePathFinder.legacy.get() - l0, SimEdgePathFinder.simMisses.get() - m0,
                SimEdgePathFinder.inconsistencies.get() - i0, SimEdgePathFinder.fallbacks.get() - f0, SimEdgePathFinder.capped.get() - c0, SimEdgePathFinder.statesCreated.get() - st0));
        sb.append("| terrain | both ok | A* path, legacy price | A* path, sim price | sim-edge path, legacy price | sim-edge path, sim price | sim price ratio (sim-edge / A*) | turns A* | turns sim-edge |\n|---|---|---|---|---|---|---|---|---|\n");
        for (baritone.pathing.calc.bench.Terrain t : terrains) {
            double al = 0, as = 0, sl = 0, ss = 0, ta = 0, ts = 0;
            List<Double> ratios = new ArrayList<>();
            int n = 0;
            for (PathBench.Query q : qs) {
                if (q.terrain() != t) continue;
                SyntheticWorld w = PathBench.world(t);
                Optional<IPath> a = quiet(PathBench.ASTAR.create(q.start(), q.goal(), PathBench.context(w)));
                Optional<IPath> m = quiet(PathBench.finder("simedge").create(q.start(), q.goal(), PathBench.context(w)));
                if (a.isEmpty() || m.isEmpty() || !q.goal().isInGoal(a.get().getDest()) || !q.goal().isInGoal(m.get().getDest())) continue;
                n++;
                SimEdge e = edge(w);
                List<SimEdge.Priced> pa = e.price(cells(a.get()), null), pm = edge(w).price(cells(m.get()), null);
                al += legacySum(pa);
                as += sum(pa);
                sl += legacySum(pm);
                ss += sum(pm);
                ratios.add(sum(pm) / sum(pa));
                ta += MaestroTest.turns(a.get(), 1);
                ts += MaestroTest.turns(m.get(), 1);
            }
            int d = Math.max(1, n);
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f | %.1f | %.1f | %.4f | %.1f | %.1f |%n", t, n, al / d, as / d, sl / d, ss / d,
                    PathBench.pct(ratios, .5), ta / d, ts / d));
        }
        PathBench.write("simedge.md", sb.toString());
    }

    static SyntheticWorld pillars() {
        SyntheticWorld w = floor(48, 48);
        for (int x = 8; x < 40; x += 4) for (int z = 6; z < 40; z += 5) {
            w.set(x, Y, z, Blocks.STONE.defaultBlockState());
            w.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
        }
        return w;
    }

    static List<int[]> cells(IPath p) {
        List<int[]> out = new ArrayList<>();
        for (BetterBlockPos b : p.positions()) out.add(new int[]{b.x, b.y, b.z});
        return out;
    }

    static double sum(List<SimEdge.Priced> ps) {
        double s = 0;
        for (SimEdge.Priced p : ps) s += p.sim();
        return s;
    }

    static double legacySum(List<SimEdge.Priced> ps) {
        double s = 0;
        for (SimEdge.Priced p : ps) s += p.legacy();
        return s;
    }

    static int sum(int[] xs) {
        int s = 0;
        for (int x : xs) s += x;
        return s;
    }
}
