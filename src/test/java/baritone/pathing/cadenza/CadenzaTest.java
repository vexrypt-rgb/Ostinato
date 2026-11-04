/*
 * Ostinato Cadenza: task-aware execution states (candidate cells, terminal costs, the goal region, the search charge).
 */
package baritone.pathing.cadenza;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class CadenzaTest {

    static final int Y = 64; // feet on the floor
    static final double REACH = 4.5;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Before
    public void reset() {
        CadenzaStats.reset();
    }

    static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    static BlockState ore() {
        return Blocks.DIAMOND_ORE.defaultBlockState();
    }

    /** A 32 by 32 stone floor at y 63, feet at 64. */
    static SyntheticWorld floor() {
        SyntheticWorld w = new SyntheticWorld(32, 32);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                w.set(x, 0, z, Blocks.BEDROCK.defaultBlockState());
                for (int y = 1; y < Y; y++) {
                    w.set(x, y, z, stone());
                }
            }
        }
        return w;
    }

    static Cadenza.Result mine(SyntheticWorld w, int x, int y, int z) {
        return Cadenza.candidates(PathBench.context(w), Cadenza.Target.mine(new BlockPos(x, y, z), REACH, false), Cadenza.Forbidden.NONE);
    }

    /** Every candidate: standable, not in or on the target, within the usable reach, and its centre eye sees the target. */
    static void assertSound(SyntheticWorld w, Cadenza.Result r) {
        CalculationContext ctx = PathBench.context(w);
        Cadenza.Target t = r.target;
        AABB box = new AABB(t.pos.x, t.pos.y, t.pos.z, t.pos.x + 1, t.pos.y + 1, t.pos.z + 1);
        double usable = t.reach - Cadenza.REACH_MARGIN;
        for (Cadenza.Candidate c : r.candidates) {
            assertTrue(c + " standable", Cadenza.canStand(ctx, t, c.x, c.y, c.z, Cadenza.Forbidden.NONE));
            assertFalse(c + " inside the target", c.x == t.pos.x && c.z == t.pos.z && (c.y == t.pos.y || c.y + 1 == t.pos.y));
            if (t.kind == Cadenza.Kind.MINE) {
                assertFalse(c + " on the target", c.x == t.pos.x && c.z == t.pos.z && c.y - 1 == t.pos.y);
            }
            assertTrue(c + " within reach", box.distanceToSqr(new Vec3(c.x + 0.5, c.y + t.eye, c.z + 0.5)) <= usable * usable + 1e-9);
            assertTrue(c + " aim within reach", c.aimDistance <= usable + 1e-9);
            assertTrue(c + " terminal >= 0", c.terminal >= 0);
        }
        for (int i = 1; i < r.candidates.size(); i++) {
            assertTrue("best first", r.candidates.get(i - 1).terminal <= r.candidates.get(i).terminal);
        }
    }

    static boolean has(Cadenza.Result r, int x, int y, int z) {
        for (Cadenza.Candidate c : r.candidates) {
            if (c.x == x && c.y == y && c.z == z) return true;
        }
        return false;
    }

    @Test
    public void settingsDefaultOff() {
        assertFalse(Baritone.settings().cadenza.value);
        assertFalse(Baritone.settings().cadenzaShortHorizon.value);
        assertFalse(Baritone.settings().cadenzaDebug.value);
        assertEquals(8, (int) Baritone.settings().cadenzaMaxTargets.value);
    }

    @Test
    public void exposedOre() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        Cadenza.Result r = mine(w, 10, Y, 10);
        assertEquals(Cadenza.Status.OK, r.status);
        assertSound(w, r);
        assertTrue(has(r, 11, Y, 10));
        assertTrue(has(r, 9, Y, 10));
        // nothing two blocks or more above the floor stands (no floor there)
        for (Cadenza.Candidate c : r.candidates) assertEquals(Y, c.y);
        // a neighbour costs at most the steeper look (under a tick) more than a cell 3 away
        double near = r.candidates.stream().filter(c -> c.x == 11 && c.z == 10).findFirst().orElseThrow().terminal;
        double far = r.candidates.stream().filter(c -> c.x == 13 && c.z == 10).findFirst().orElseThrow().terminal;
        assertTrue(near + " vs " + far, near <= far + 1.0);
    }

    @Test
    public void oreInWall() {
        SyntheticWorld w = floor();
        for (int x = 12; x <= 14; x++) for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 6; y++) w.set(x, y, z, stone());
        w.set(12, Y + 1, 10, ore());
        Cadenza.Result r = mine(w, 12, Y + 1, 10);
        assertEquals(Cadenza.Status.OK, r.status);
        assertSound(w, r);
        for (Cadenza.Candidate c : r.candidates) assertTrue(c + " west of the wall", c.x <= 11);
        assertTrue(has(r, 11, Y, 10));

    }

    @Test
    public void oreAbove() {
        SyntheticWorld w = floor();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) w.set(x, Y + 3, z, stone()); // ceiling, 3 clear cells
        w.set(10, Y + 3, 10, ore());
        Cadenza.Result r = mine(w, 10, Y + 3, 10);
        assertEquals(Cadenza.Status.OK, r.status);
        assertSound(w, r);
        assertTrue("straight under it", has(r, 10, Y, 10));
        for (Cadenza.Candidate c : r.candidates) {
            assertTrue(c.toString(), c.y == Y ? c.pitch < 0 : c.y == Y + 4 && c.pitch > 0); // up from below, down from the roof
        }
    }

    @Test
    public void oreBelowNeverStoodOn() {
        SyntheticWorld w = floor();
        w.set(10, Y - 1, 10, ore());
        Cadenza.Result r = mine(w, 10, Y - 1, 10);
        assertEquals(Cadenza.Status.OK, r.status);
        assertSound(w, r);
        assertFalse(has(r, 10, Y, 10));
        assertTrue(has(r, 11, Y, 10));
        for (Cadenza.Candidate c : r.candidates) assertTrue(c.pitch > 0); // looking down
    }

    @Test
    public void occludedOre() {
        SyntheticWorld w = floor();
        w.set(10, Y - 3, 10, ore()); // buried: standable cells above, no line of sight
        Cadenza.Result r = mine(w, 10, Y - 3, 10);
        assertEquals(Cadenza.Status.OCCLUDED, r.status);
        assertTrue(r.standable > 0);
        assertTrue(r.candidates.isEmpty());
    }

    @Test
    public void onlyCellsThatSeeIt() {
        SyntheticWorld w = floor();
        // a 2 high wall at x 12 with the ore behind it at x 13, one window at z 10
        for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 3; y++) {
            w.set(12, y, z, stone());
            w.set(13, y, z, stone());
            w.set(14, y, z, stone());
        }
        w.set(12, Y + 1, 10, Blocks.AIR.defaultBlockState());
        w.set(13, Y + 1, 10, ore());
        Cadenza.Result r = mine(w, 13, Y + 1, 10);
        assertEquals(Cadenza.Status.OK, r.status);
        assertSound(w, r);
        for (Cadenza.Candidate c : r.candidates) assertTrue(c + " sees through the window", c.x <= 11 && Math.abs(c.z - 10) <= 2);
    }

    @Test
    public void lavaCellsRefused() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        w.set(8, Y - 1, 10, Blocks.LAVA.defaultBlockState());
        Cadenza.Result r = mine(w, 10, Y, 10);
        assertEquals(Cadenza.Status.OK, r.status);
        for (Cadenza.Candidate c : r.candidates) {
            assertFalse(c + " next to lava", Math.abs(c.x - 8) <= 1 && Math.abs(c.z - 10) <= 1);
        }
        // fully ringed by lava within reach: nothing safe
        SyntheticWorld v = floor();
        v.set(10, Y, 10, ore());
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) if (!(x == 10 && z == 10)) v.set(x, Y - 1, z, Blocks.LAVA.defaultBlockState());
        Cadenza.Result s = mine(v, 10, Y, 10);
        assertFalse(s.ok());
        assertEquals(Cadenza.Status.NO_STANDING, s.status);
    }

    @Test
    public void dropEdgeCosts() {
        SyntheticWorld w = floor();
        for (int z = 0; z < 32; z++) for (int y = 1; y < Y; y++) w.set(12, y, z, Blocks.AIR.defaultBlockState()); // a deep trench at x 12
        Cadenza.Target t = Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, false);
        assertEquals(Cadenza.DEEP_EDGE_TICKS, Cadenza.risk(w, t, 11, Y, 12), 1e-9);
        assertEquals(0, Cadenza.risk(w, t, 9, Y, 12), 1e-9);
        // an edge cell loses to a safe cell that is otherwise as good
        w.set(10, Y, 10, ore());
        Cadenza.Result r = mine(w, 10, Y, 10);
        double edge = r.candidates.stream().filter(c -> c.x == 11 && c.z == 10).findFirst().orElseThrow().terminal;
        double safe = r.candidates.stream().filter(c -> c.x == 9 && c.z == 10).findFirst().orElseThrow().terminal;
        assertEquals(Cadenza.DEEP_EDGE_TICKS, edge - safe, 1e-9);
    }

    @Test
    public void fallingBlockAboveCosts() {
        SyntheticWorld w = floor();
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) w.set(x, Y + 3, z, stone());
        w.set(10, Y + 3, 10, ore());
        w.set(10, Y + 4, 10, Blocks.GRAVEL.defaultBlockState());
        Cadenza.Target t = Cadenza.Target.mine(new BlockPos(10, Y + 3, 10), REACH, false);
        assertEquals(Cadenza.FALLING_TICKS, Cadenza.risk(w, t, 10, Y, 10), 1e-9);
        assertEquals(0, Cadenza.risk(w, t, 11, Y, 10), 1e-9);
    }

    @Test
    public void reachBoundary() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        Cadenza.Result far = mine(w, 10, Y, 10);
        Cadenza.Result near = Cadenza.candidates(PathBench.context(w), Cadenza.Target.mine(new BlockPos(10, Y, 10), 2.5, false), Cadenza.Forbidden.NONE);
        assertSound(w, far);
        assertSound(w, near);
        assertTrue(near.candidates.size() < far.candidates.size());
        int maxFar = far.candidates.stream().mapToInt(c -> Math.max(Math.abs(c.x - 10), Math.abs(c.z - 10))).max().orElseThrow();
        assertTrue("4.5 reach reaches 3+ cells away, got " + maxFar, maxFar >= 3);
        for (Cadenza.Candidate c : near.candidates) assertTrue(c.toString(), Math.max(Math.abs(c.x - 10), Math.abs(c.z - 10)) <= 2);
    }

    @Test
    public void invalidTargets() {
        SyntheticWorld w = floor();
        CalculationContext ctx = PathBench.context(w);
        assertEquals(Cadenza.Status.TARGET_INVALID, mine(w, 10, Y + 1, 10).status); // air
        w.set(5, Y, 5, Blocks.WATER.defaultBlockState());
        assertEquals(Cadenza.Status.TARGET_INVALID, mine(w, 5, Y, 5).status);
        assertEquals(Cadenza.Status.TARGET_INVALID,
                Cadenza.candidates(ctx, Cadenza.Target.place(new BlockPos(10, Y - 1, 10), REACH), Cadenza.Forbidden.NONE).status); // solid
        assertEquals(Cadenza.Status.TARGET_INVALID,
                Cadenza.candidates(ctx, Cadenza.Target.place(new BlockPos(10, Y + 10, 10), REACH), Cadenza.Forbidden.NONE).status); // nothing to place against
    }

    @Test
    public void forbiddenSupport() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        Cadenza.Result r = Cadenza.candidates(PathBench.context(w), Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, false),
                (x, y, z) -> x == 11 && y == Y - 1 && z == 10);
        assertFalse(has(r, 11, Y, 10));
        assertTrue(has(r, 9, Y, 10));
    }

    @Test
    public void dropWalkCharged() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        Cadenza.Result plain = mine(w, 10, Y, 10);
        Cadenza.Result drop = Cadenza.candidates(PathBench.context(w), Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, true), Cadenza.Forbidden.NONE);
        double a = plain.candidates.stream().filter(c -> c.x == 13 && c.z == 10).findFirst().orElseThrow().terminal;
        double b = drop.candidates.stream().filter(c -> c.x == 13 && c.z == 10).findFirst().orElseThrow().terminal;
        assertEquals(2 * baritone.api.pathing.movement.ActionCosts.WALK_ONE_BLOCK_COST, b - a, 1e-9);
    }

    @Test
    public void placementOnTheFloor() {
        SyntheticWorld w = floor();
        Cadenza.Target t = Cadenza.Target.place(new BlockPos(10, Y, 10), REACH);
        Cadenza.Result r = Cadenza.candidates(PathBench.context(w), t, Cadenza.Forbidden.NONE);
        assertEquals(Cadenza.Status.OK, r.status);
        assertSound(w, r);
        for (Cadenza.Candidate c : r.candidates) assertEquals(Direction.UP, c.face); // the floor's top face
        // right beside the target costs a little more than diagonal
        double beside = r.candidates.stream().filter(c -> c.x == 11 && c.z == 10).findFirst().orElseThrow().terminal;
        double diag = r.candidates.stream().filter(c -> c.x == 11 && c.z == 11).findFirst().orElseThrow().terminal;
        assertTrue(beside > diag);
    }

    @Test
    public void placementAgainstAWall() {
        SyntheticWorld w = floor();
        // place into the cell at x 11, y 66 (in the air) against a wall at x 12; the floor is far below it
        for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 5; y++) w.set(12, y, z, stone());
        Cadenza.Target t = Cadenza.Target.place(new BlockPos(11, Y + 2, 10), REACH);
        Cadenza.Result r = Cadenza.candidates(PathBench.context(w), t, Cadenza.Forbidden.NONE);
        assertEquals(Cadenza.Status.OK, r.status);
        assertSound(w, r);
        for (Cadenza.Candidate c : r.candidates) {
            assertEquals(Direction.WEST, c.face);
            assertTrue(c.x <= 11);
        }
    }

    @Test
    public void containerOpenSide() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, Blocks.CHEST.defaultBlockState());
        Cadenza.Result open = Cadenza.candidates(PathBench.context(w), Cadenza.Target.use(new BlockPos(10, Y, 10), REACH), Cadenza.Forbidden.NONE);
        assertEquals(Cadenza.Status.OK, open.status);
        assertSound(w, open);
        // boxed in except the west side: only cells to the west
        for (int[] d : new int[][]{{1, 0}, {0, 1}, {0, -1}}) for (int y = Y; y <= Y + 1; y++) w.set(10 + d[0], y, 10 + d[1], stone());
        w.set(10, Y + 1, 10, stone());
        Cadenza.Result boxed = Cadenza.candidates(PathBench.context(w), Cadenza.Target.use(new BlockPos(10, Y, 10), REACH), Cadenza.Forbidden.NONE);
        assertEquals(Cadenza.Status.OK, boxed.status);
        assertSound(w, boxed);
        for (Cadenza.Candidate c : boxed.candidates) assertTrue(c + " west", c.x < 10);
    }

    @Test
    public void goalRegionAndShareBonus() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        w.set(10, Y, 12, ore());
        Cadenza.Result a = mine(w, 10, Y, 10), b = mine(w, 10, Y, 12);
        CadenzaGoal plain = new CadenzaGoal(List.of(a, b));
        CadenzaGoal shared = new CadenzaGoal(List.of(a, b), 4.0);
        assertTrue(plain.hasCandidates());
        assertEquals(2, plain.reachCount(10, Y, 11)); // between the two ores
        assertTrue(plain.isInGoal(10, Y, 11));
        assertFalse(plain.isInGoal(10, Y, 10)); // in the ore
        double p = plain.terminalCost(10, Y, 11), s = shared.terminalCost(10, Y, 11);
        assertEquals(Math.max(0, p - 4.0), s, 1e-9);
        // a cell one target reaches is untouched
        int[] only = null;
        for (Cadenza.Candidate c : a.candidates) if (plain.reachCount(c.x, c.y, c.z) == 1) { only = new int[]{c.x, c.y, c.z}; break; }
        assertNotNull(only);
        assertEquals(plain.terminalCost(only[0], only[1], only[2]), shared.terminalCost(only[0], only[1], only[2]), 1e-9);
        // admissible-ish: 0 inside a candidate box, never more than GoalBlock to the nearest candidate
        assertEquals(0, plain.heuristic(10, Y, 11), 1e-9);
        for (Cadenza.Candidate c : a.candidates) {
            assertTrue(plain.heuristic(2, Y, 2) <= GoalBlock.calculate(2 - c.x, Y - c.y, 2 - c.z) + 1e-9);
        }
    }

    @Test
    public void rebuiltGoalIsEqual() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        Cadenza.Result a = mine(w, 10, Y, 10), b = mine(w, 10, Y, 10);
        assertNotSame(a, b);
        CadenzaGoal g1 = new CadenzaGoal(List.of(a), new GoalBlock(25, Y, 25));
        CadenzaGoal g2 = new CadenzaGoal(List.of(b), new GoalBlock(25, Y, 25));
        assertEquals(g1, g2);
        assertEquals(g1.hashCode(), g2.hashCode());
        assertNotEquals(g1, new CadenzaGoal(List.of(a), new GoalBlock(24, Y, 25)));
        w.set(10, Y - 1, 10, ore());
        assertNotEquals(g1, new CadenzaGoal(List.of(mine(w, 10, Y - 1, 10)), new GoalBlock(25, Y, 25)));
    }

    @Test
    public void extrasRideAlongAtNoCharge() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        Cadenza.Result a = mine(w, 10, Y, 10);
        Cadenza.Candidate c = a.best();
        CadenzaGoal g = new CadenzaGoal(List.of(a), new GoalBlock(25, Y, 25), new GoalBlock(c.x, c.y, c.z));
        assertTrue(g.isInGoal(25, Y, 25));
        assertEquals(0, g.terminalCost(25, Y, 25), 1e-9);
        assertEquals(0, g.terminalCost(c.x, c.y, c.z), 1e-9); // also an extra
        CadenzaGoal none = new CadenzaGoal(List.of(mine(w, 10, Y - 3, 10)), new GoalBlock(25, Y, 25));
        assertFalse(none.hasCandidates());
        assertEquals(new GoalBlock(25, Y, 25).heuristic(3, Y, 3), none.heuristic(3, Y, 3), 1e-9);
    }

    static IPath search(SyntheticWorld w, BetterBlockPos start, Goal goal) {
        CalculationContext ctx = PathBench.context(w);
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream()));
        try {
            return PathBench.plan(PathBench.ASTAR.create(start, goal, ctx)).orElseThrow();
        } finally {
            System.setOut(out);
        }
    }

    static Cadenza.Result handmade(int[]... cells) {
        List<Cadenza.Candidate> cs = new ArrayList<>();
        for (int[] c : cells) cs.add(new Cadenza.Candidate(c[0], c[1], c[2], c[3], 1, 0, 4, null));
        cs.sort((a, b) -> Double.compare(a.terminal, b.terminal));
        return new Cadenza.Result(Cadenza.Target.mine(new BlockPos(20, Y, 20), REACH, false), Cadenza.Status.OK, cs, cs.size(), cs.size(), 0);
    }

    @Test
    public void searchPaysTheTerminalCost() {
        SyntheticWorld w = floor();
        BetterBlockPos start = new BetterBlockPos(5, Y, 5);
        // near cell: 2 blocks away, 50 ticks to finish there; far cell: 7 blocks away, nothing left to pay
        Goal expensiveNear = new CadenzaGoal(List.of(handmade(new int[]{7, Y, 5, 50}, new int[]{12, Y, 5, 0})));
        assertEquals(new BetterBlockPos(12, Y, 5), search(w, start, expensiveNear).getDest());
        assertTrue(CadenzaStats.terminalCharges() > 0);
        // a small charge does not justify the walk
        Goal cheapNear = new CadenzaGoal(List.of(handmade(new int[]{7, Y, 5, 5}, new int[]{12, Y, 5, 0})));
        assertEquals(new BetterBlockPos(7, Y, 5), search(w, start, cheapNear).getDest());
        // without terminal costs the nearest goal cell wins, as before
        Goal plain = new GoalComposite(new GoalBlock(7, Y, 5), new GoalBlock(12, Y, 5));
        assertEquals(new BetterBlockPos(7, Y, 5), search(w, start, plain).getDest());
    }

    @Test
    public void searchWithZeroTerminalCostsIsPlain() {
        SyntheticWorld w = floor();
        BetterBlockPos start = new BetterBlockPos(5, Y, 5);
        Goal zero = new CadenzaGoal(List.of(handmade(new int[]{7, Y, 5, 0}, new int[]{12, Y, 5, 0})));
        IPath a = search(w, start, zero);
        IPath b = search(w, start, new GoalComposite(new GoalBlock(7, Y, 5), new GoalBlock(12, Y, 5)));
        assertEquals(b.positions(), a.positions());
        assertEquals(0, CadenzaStats.terminalCharges());
    }

    @Test
    public void searchFindsARealMiningCell() {
        SyntheticWorld w = floor();
        for (int x = 12; x <= 14; x++) for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 6; y++) w.set(x, y, z, stone());
        w.set(12, Y + 1, 10, ore());
        Cadenza.Result r = mine(w, 12, Y + 1, 10);
        CadenzaGoal g = new CadenzaGoal(List.of(r));
        IPath p = search(w, new BetterBlockPos(3, Y, 3), g);
        BetterBlockPos d = p.getDest();
        assertTrue(g.isCandidate(d.x, d.y, d.z));
        assertTrue(d.x <= 11);
    }

    @Test
    public void sessionLifecycle() {
        CadenzaSession s = new CadenzaSession();
        BlockPos t = new BlockPos(1, 2, 3);
        s.attempt(t, 5);
        s.attempt(t, 5); // same target: counted once
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.ATTEMPTED));
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.POSE_REACHED));
        s.confirm();
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.CONFIRMED));
        assertNull(s.current());
        s.attempt(t, 3);
        for (int i = 0; i < 3; i++) assertNull(s.tick());
        assertEquals(t, s.tick()); // past budget: failed, handed back once
        assertNull(s.tick());
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.FAILED));
        s.attempt(new BlockPos(9, 9, 9), 100);
        s.invalidate();
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.INVALIDATED));
        s.attempt(new BlockPos(8, 8, 8), 100);
        s.fail();
        assertEquals(2, CadenzaStats.phases(CadenzaStats.Phase.FAILED));
    }

    @Test
    public void sessionDropsUnusableCells() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        CalculationContext ctx = PathBench.context(w);
        CadenzaSession s = new CadenzaSession();
        AtomicLong now = new AtomicLong(1000);
        s.clock = now::get;
        List<Cadenza.Target> ts = List.of(Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, false));
        CadenzaGoal g = s.goal(ctx, ts, Cadenza.Forbidden.NONE, false, r -> fail("has cells"), new ArrayList<>());
        Cadenza.Candidate best = g.results().get(0).best();
        BetterBlockPos feet = new BetterBlockPos(best.x, best.y, best.z);
        assertTrue(s.inCandidate(feet));
        for (int i = 0; i < CadenzaSession.POSE_MISS_TICKS; i++) s.nothingReachable(feet, true);
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.POSE_UNUSABLE));
        CadenzaGoal g2 = s.goal(ctx, ts, Cadenza.Forbidden.NONE, false, r -> fail("still has cells"), new ArrayList<>());
        assertFalse(g2.isCandidate(feet.x, feet.y, feet.z));
        assertTrue(g2.hasCandidates());
        now.addAndGet(CadenzaSession.BAD_CELL_MS + 1); // the cell comes back
        CadenzaGoal g3 = s.goal(ctx, ts, Cadenza.Forbidden.NONE, false, r -> fail("has cells"), new ArrayList<>());
        assertTrue(g3.isCandidate(feet.x, feet.y, feet.z));
        // not in a candidate, or in the air: never counts
        s.nothingReachable(new BetterBlockPos(30, Y, 30), true);
        for (int i = 0; i < 2 * CadenzaSession.POSE_MISS_TICKS; i++) s.nothingReachable(feet, false);
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.POSE_UNUSABLE));
    }

    @Test
    public void sessionReportsNoCell() {
        SyntheticWorld w = floor();
        w.set(10, Y - 3, 10, ore()); // buried
        CadenzaSession s = new CadenzaSession();
        List<Cadenza.Result> missed = new ArrayList<>();
        List<Goal> extras = new ArrayList<>();
        CadenzaGoal g = s.goal(PathBench.context(w), List.of(Cadenza.Target.mine(new BlockPos(10, Y - 3, 10), REACH, false)),
                Cadenza.Forbidden.NONE, false, r -> {
                    missed.add(r);
                    extras.add(new GoalBlock(r.target.pos.above()));
                }, extras);
        assertEquals(1, missed.size());
        assertEquals(Cadenza.Status.OCCLUDED, missed.get(0).status);
        assertEquals(1, CadenzaStats.phases(CadenzaStats.Phase.NO_CANDIDATE));
        assertTrue(g.isInGoal(10, Y - 2, 10)); // the caller's fallback rides along
        assertFalse(g.hasCandidates());
    }

    @Test
    public void sessionAppliesTheCallersCellFilter() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        CadenzaSession s = new CadenzaSession();
        List<Cadenza.Target> ts = List.of(Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, false));
        CadenzaGoal g = s.goal(PathBench.context(w), ts, Cadenza.Forbidden.NONE, (t, x, y, z) -> x < 10, false, r -> fail("has cells"), new ArrayList<>());
        assertTrue(g.hasCandidates());
        for (Cadenza.Candidate c : g.results().get(0).candidates) assertTrue(c.x < 10);
        List<Cadenza.Result> missed = new ArrayList<>();
        s.goal(PathBench.context(w), ts, Cadenza.Forbidden.NONE, (t, x, y, z) -> false, false, missed::add, new ArrayList<>());
        assertEquals(1, missed.size());
        assertEquals(Cadenza.Status.OCCLUDED, missed.get(0).status);
    }

    @Test
    public void cacheReusesUntilTheTargetChanges() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        CalculationContext ctx = PathBench.context(w);
        CadenzaCache c = new CadenzaCache(1000);
        AtomicLong now = new AtomicLong(0);
        c.clock = now::get;
        Cadenza.Target t = Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, false);
        Cadenza.Result a = c.resolve(ctx, t, Cadenza.Forbidden.NONE);
        assertSame(a, c.resolve(ctx, t, Cadenza.Forbidden.NONE));
        w.set(10, Y, 10, Blocks.AIR.defaultBlockState()); // mined
        Cadenza.Result b = c.resolve(PathBench.context(w), t, Cadenza.Forbidden.NONE);
        assertNotSame(a, b);
        assertEquals(Cadenza.Status.TARGET_INVALID, b.status);
        w.set(10, Y, 10, ore());
        Cadenza.Result d = c.resolve(PathBench.context(w), t, Cadenza.Forbidden.NONE);
        assertTrue(d.ok());
        now.addAndGet(1000); // expired
        assertNotSame(d, c.resolve(PathBench.context(w), t, Cadenza.Forbidden.NONE));
    }

    @Test
    public void boundedWork() {
        SyntheticWorld w = floor();
        w.set(10, Y, 10, ore());
        Cadenza.Result r = mine(w, 10, Y, 10);
        assertTrue("examined " + r.examined, r.examined <= 11 * 11 * 10);
        assertTrue("rays " + r.rays, r.rays <= r.standable * 7 * 5);
    }
}
