/*
 * Ostinato Cadenza v2: the turn model, heading-aware terminal costs, shared-pose batching and goal equality.
 */
package baritone.pathing.cadenza;

import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import net.minecraft.core.BlockPos;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static baritone.pathing.cadenza.CadenzaTest.REACH;
import static baritone.pathing.cadenza.CadenzaTest.Y;
import static org.junit.Assert.*;

public class CadenzaV2Test {

    static final Cadenza.TurnModel TURN = new Cadenza.TurnModel(32, 8);

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Before
    public void reset() {
        CadenzaStats.reset();
    }

    /** {x, y, z, base, yaw, pitch} per cell; one target at (tx, Y, tz). */
    static Cadenza.Result target(int tx, int tz, double[]... cells) {
        List<Cadenza.Candidate> cs = new ArrayList<>();
        for (double[] c : cells) {
            double pitchTicks = Math.max(0, Math.abs(c[5]) - Cadenza.PITCH_FREE) * Cadenza.PITCH_TICKS_PER_DEG;
            cs.add(new Cadenza.Candidate((int) c[0], (int) c[1], (int) c[2], c[3] + pitchTicks, 1, c[5], 8, null, c[4], pitchTicks));
        }
        cs.sort((a, b) -> Double.compare(a.terminal, b.terminal));
        return new Cadenza.Result(Cadenza.Target.mine(new BlockPos(tx, Y, tz), REACH, false), Cadenza.Status.OK, cs, cs.size(), cs.size(), 0);
    }

    // --- the turn model -------------------------------------------------------------------------------------------

    @Test
    public void yawAndPitchTurnTogether() {
        assertEquals(0, TURN.ticks(0, 0), 0);
        assertEquals(1, TURN.ticks(30, 30), 0); // both axes in one tick, not two
        assertEquals(1, TURN.ticks(32, -32), 0);
        assertEquals(2, TURN.ticks(33, 0), 0);
        assertEquals(2, TURN.ticks(10, 64), 0);
        assertEquals(3, TURN.ticks(65, 65), 0);
        assertEquals(6, TURN.ticks(180, 0), 0);
        // the cap is the process's: a slower hand takes longer
        assertEquals(9, new Cadenza.TurnModel(10, 8).ticks(90, 0), 0);
    }

    @Test
    public void headingAndWrap() {
        assertEquals(-90, Cadenza.TurnModel.heading(0, 0, 1, 0), 1e-9); // walking +x faces east, yaw -90
        assertEquals(0, Cadenza.TurnModel.heading(0, 0, 0, 1), 1e-9); // +z: south, yaw 0
        assertEquals(90, Cadenza.TurnModel.heading(1, 0, 0, 0), 1e-9);
        assertEquals(180, Math.abs(Cadenza.TurnModel.heading(0, 1, 0, 0)), 1e-9);
        assertEquals(-45, Cadenza.TurnModel.heading(0, 0, 1, 1), 1e-9);
        assertTrue(Double.isNaN(Cadenza.TurnModel.heading(3, 4, 3, 4))); // straight up or down, or no step
        assertEquals(20, Cadenza.TurnModel.wrap(-340), 1e-9);
        assertEquals(-170, Cadenza.TurnModel.wrap(190), 1e-9);
        // the short way round: 170 to -170 is 20 degrees, one tick
        assertEquals(1, TURN.between(170, 8, -170, 8), 0);
    }

    @Test
    public void unknownHeadingIsConservative() {
        // no heading: a quarter turn is charged whatever the aim
        assertEquals(3, TURN.fromArrival(Double.NaN, -90, 8), 0);
        assertEquals(3, TURN.fromArrival(Double.NaN, 45, 8), 0);
        // known: the mean over the step's yaw and 15 either side. On target: 15, 0, 15 of yaw, (1 + 0 + 1) / 3
        assertEquals(2 / 3.0, TURN.fromArrival(-90, -90, 8), 1e-12);
        // behind: 195, 180, 165 of yaw, (7 + 6 + 6) / 3
        assertEquals(19 / 3.0, TURN.fromArrival(90, -90, 8), 1e-12);
        // 52 of pitch from the walk's 8 down covers the yaw spread: two ticks whatever the heading error
        assertEquals(2, TURN.fromArrival(-90, -90, 60), 1e-12);
        // never cheaper with an unknown heading than with a known one on target
        assertTrue(TURN.fromArrival(Double.NaN, -90, 8) > TURN.fromArrival(-90, -90, 8));
        assertEquals(TURN.fromArrival(Double.NaN, 0, 8), Math.ceil(Cadenza.TurnModel.UNKNOWN_YAW / 32), 0);
    }

    @Test
    public void candidatesCarryTheirAimYaw() {
        SyntheticWorld w = CadenzaTest.floor();
        w.set(10, Y, 10, CadenzaTest.ore());
        Cadenza.Result r = CadenzaTest.mine(w, 10, Y, 10);
        assertTrue(r.ok());
        boolean west = false, north = false;
        for (Cadenza.Candidate c : r.candidates) {
            if (c.x == 8 && c.z == 10 && c.y == Y) {
                assertEquals(-90, c.yaw, 1e-6); // the target is east
                west = true;
            }
            if (c.x == 10 && c.z == 8 && c.y == Y) {
                assertEquals(0, c.yaw, 1e-6); // the target is south
                north = true;
            }
            assertEquals(c.terminal - c.pitchTicks, c.base(), 1e-12);
            assertEquals(Math.max(0, Math.abs(c.pitch) - Cadenza.PITCH_FREE) * Cadenza.PITCH_TICKS_PER_DEG, c.pitchTicks, 1e-12);
        }
        assertTrue(west && north);
    }

    // --- turn-aware terminal cost ---------------------------------------------------------------------------------

    @Test
    public void offIsV1() {
        Cadenza.Result r = target(20, 20, new double[]{5, Y, 5, 2, -90, 50});
        CadenzaGoal g = new CadenzaGoal(List.of(r));
        // the heading-aware form changes nothing when v2 is off
        assertEquals(g.terminalCost(5, Y, 5), g.terminalCost(5, Y, 5, 4, Y, 5), 1e-12);
        assertEquals(2 + 20 / 64.0, g.terminalCost(5, Y, 5), 1e-12);
    }

    @Test
    public void turnAwareChargesTheArrivalTurn() {
        Cadenza.Result r = target(20, 20, new double[]{5, Y, 5, 2, -90, 50});
        CadenzaGoal g = new CadenzaGoal(List.of(r), 0, CadenzaGoal.Options.turnAware(TURN));
        // the pitch term is replaced, not added to: arriving east (yaw -90), 42 of pitch to make, two ticks
        assertEquals(2 + 2, g.terminalCost(5, Y, 5, 4, Y, 5), 1e-12);
        // arriving west-bound: 180 of yaw give or take 15, (7 + 6 + 6) / 3 ticks
        assertEquals(2 + 19 / 3.0, g.terminalCost(5, Y, 5, 6, Y, 5), 1e-12);
        // no heading (empty path or a vertical last step): the conservative quarter turn
        assertEquals(2 + 3, g.terminalCost(5, Y, 5, 5, Y, 5), 1e-12);
        assertEquals(2 + 3, g.terminalCost(5, Y, 5, 5, Y - 1, 5), 1e-12);
        assertEquals(g.terminalCost(5, Y, 5), g.terminalCost(5, Y, 5, 5, Y, 5), 1e-12);
    }

    @Test
    public void turnAwareTakesTheCheapestTargetForTheHeading() {
        Cadenza.Result east = target(20, 20, new double[]{5, Y, 5, 3, -90, 8});
        Cadenza.Result west = target(0, 0, new double[]{5, Y, 5, 1, 90, 8});
        CadenzaGoal g = new CadenzaGoal(List.of(east, west), 0, CadenzaGoal.Options.turnAware(TURN));
        assertEquals(3 + 2 / 3.0, g.terminalCost(5, Y, 5, 4, Y, 5), 1e-12); // facing east: the east target
        assertEquals(1 + 2 / 3.0, g.terminalCost(5, Y, 5, 6, Y, 5), 1e-12); // facing west: the west one
    }

    @Test
    public void searchEndsWhereTheApproachFacesTheAim() {
        SyntheticWorld w = CadenzaTest.floor();
        BetterBlockPos start = new BetterBlockPos(5, Y, 5);
        // two cells four blocks from the start; the aim from both is east. Reached walking east, A needs no turn;
        // reached walking south, B needs a quarter turn. B is one tick cheaper heading-blind.
        Cadenza.Result r = target(20, 5, new double[]{9, Y, 5, 1, -90, 8}, new double[]{5, Y, 9, 0, -90, 8});
        assertEquals(new BetterBlockPos(5, Y, 9), CadenzaTest.search(w, start, new CadenzaGoal(List.of(r))).getDest());
        CadenzaGoal v2 = new CadenzaGoal(List.of(r), 0, CadenzaGoal.Options.turnAware(TURN));
        assertEquals(new BetterBlockPos(9, Y, 5), CadenzaTest.search(w, start, v2).getDest());
    }

    // --- batching -------------------------------------------------------------------------------------------------

    @Test
    public void batchPricesTheWholeJob() {
        // P reaches all three (2 ticks each, aims 30 degrees apart); Q reaches only the first, free, but leaves two
        Cadenza.Result t0 = target(20, 20, new double[]{10, Y, 10, 2, -90, 8}, new double[]{20, Y, 20, 0, -90, 8});
        Cadenza.Result t1 = target(21, 20, new double[]{10, Y, 10, 2, -60, 8});
        Cadenza.Result t2 = target(22, 20, new double[]{10, Y, 10, 2, -120, 8});
        CadenzaGoal g = new CadenzaGoal(List.of(t0, t1, t2), 0, CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.ANY));
        // P: 3 x 2 + two 30 degree turns (a tick each) + the arrival onto an end of the sweep
        assertEquals(6 + 2 + 3, g.terminalCost(10, Y, 10), 1e-12); // heading unknown: 90 of yaw to -120 or -60
        // facing -90: 30 to either end, give or take 15: (2 + 1 + 1) / 3
        assertEquals(6 + 2 + 4 / 3.0, g.terminalCost(10, Y, 10, 9, Y, 10), 1e-12);
        // Q: its own 0, two repositions, the walk to the nearest of the rest (P's cell), and its arrival
        double q = 2 * CadenzaGoal.Options.REPOSITION_TICKS + GoalBlock.calculate(10, 0, 10) + 3;
        assertEquals(q, g.terminalCost(20, Y, 20), 1e-9);
        assertTrue(g.terminalCost(10, Y, 10) < g.terminalCost(20, Y, 20));
        // heading-blind v1 would take Q (0 < 2)
        CadenzaGoal v1 = new CadenzaGoal(List.of(t0, t1, t2));
        assertTrue(v1.terminalCost(20, Y, 20) < v1.terminalCost(10, Y, 10));
    }

    @Test
    public void batchSweepsTheShortArc() {
        Cadenza.Result a = target(20, 20, new double[]{10, Y, 10, 0, 170, 8});
        Cadenza.Result b = target(21, 20, new double[]{10, Y, 10, 0, -170, 8});
        CadenzaGoal g = new CadenzaGoal(List.of(a, b), 0, CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.ANY));
        // 20 degrees across the back, one tick; arriving facing 180, 10 to either end (25 at most), one tick
        assertEquals(1 + 1, g.terminalCost(10, Y, 10, 10, Y, 11), 1e-12);
    }

    @Test
    public void strictBatchKeepsTheCallersOrder() {
        Cadenza.Result t0 = target(20, 20, new double[]{10, Y, 10, 1, -90, 8}, new double[]{12, Y, 12, 1, -90, 8});
        Cadenza.Result t1 = target(21, 20, new double[]{10, Y, 10, 1, 90, 8}, new double[]{14, Y, 14, 0, 90, 8});
        CadenzaGoal g = new CadenzaGoal(List.of(t0, t1), 0, CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.STRICT));
        // a cell that cannot do the first target is not a goal cell, though it can do the second
        assertFalse(g.isInGoal(14, Y, 14));
        assertTrue(g.isInGoal(10, Y, 10) && g.isInGoal(12, Y, 12));
        // the first, then the second: 180 of yaw between them, six ticks; arriving onto the first only
        assertEquals(1 + 1 + 6 + 2 / 3.0, g.terminalCost(10, Y, 10, 9, Y, 10), 1e-12);
        // facing the second does not help: the first comes first
        assertEquals(1 + 1 + 6 + 19 / 3.0, g.terminalCost(10, Y, 10, 11, Y, 10), 1e-12);
        // only the first done from (12, 12): the second costs a reposition and the walk to its cells' box (which
        // holds this cell: the walk is a lower bound, here 0)
        assertEquals(1 + CadenzaGoal.Options.REPOSITION_TICKS + 0 + 2 / 3.0, g.terminalCost(12, Y, 12, 11, Y, 12), 1e-9);
    }

    @Test
    public void batchOnRealOres() {
        // a vein of three in a wall: some cell reaches all of them, and that is where the batch wants to be
        SyntheticWorld w = CadenzaTest.floor();
        for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 5; y++) w.set(14, y, z, CadenzaTest.stone());
        w.set(14, Y + 1, 9, CadenzaTest.ore());
        w.set(14, Y + 1, 10, CadenzaTest.ore());
        w.set(14, Y + 1, 11, CadenzaTest.ore());
        List<Cadenza.Result> rs = List.of(CadenzaTest.mine(w, 14, Y + 1, 9), CadenzaTest.mine(w, 14, Y + 1, 10), CadenzaTest.mine(w, 14, Y + 1, 11));
        CadenzaGoal g = new CadenzaGoal(rs, 0, CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.ANY));
        BetterBlockPos d = CadenzaTest.search(w, new BetterBlockPos(3, Y, 10), g).getDest();
        assertEquals(3, g.reachCount(d.x, d.y, d.z));
    }

    // --- real candidates through the real search ---------------------------------------------------------------------

    /** The search's own cost to the path's end (the last node's g; movements need a live Baritone to assemble). */
    static double travel(baritone.api.pathing.calc.IPath p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("nodes");
            f.setAccessible(true);
            List<?> nodes = (List<?>) f.get(p);
            return ((baritone.pathing.calc.PathNode) nodes.getLast()).cost;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** travel + the v2 terminal at the path's end, arriving from its last step. */
    static double total(baritone.api.pathing.calc.IPath p, CadenzaGoal v2) {
        List<BetterBlockPos> ps = p.positions();
        BetterBlockPos d = ps.getLast(), from = ps.size() > 1 ? ps.get(ps.size() - 2) : d;
        return travel(p) + v2.terminalCost(d.x, d.y, d.z, from.x, from.y, from.z);
    }

    @Test
    public void realPlacementRowSearchIsOptimalUnderTheTurnModel() {
        SyntheticWorld w = CadenzaTest.floor();
        var ctx = PathBench.context(w);
        List<Cadenza.Result> rs = new ArrayList<>();
        for (int x = 12; x <= 14; x++) {
            rs.add(Cadenza.candidates(ctx, Cadenza.Target.place(new BlockPos(x, Y, 10), REACH), Cadenza.Forbidden.NONE));
        }
        for (CadenzaGoal.Options o : List.of(CadenzaGoal.Options.turnAware(TURN), CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.ANY))) {
            CadenzaGoal v1 = new CadenzaGoal(rs), v2 = new CadenzaGoal(rs, 0, o);
            BetterBlockPos start = new BetterBlockPos(2, Y, 10);
            var p1 = CadenzaTest.search(w, start, v1);
            var p2 = CadenzaTest.search(w, start, v2);
            BetterBlockPos d = p2.getDest();
            assertTrue(v2.isCandidate(d.x, d.y, d.z));
            // under v2's own objective, where v2 ends is no worse than where v1 ends
            assertTrue(o + ": " + total(p2, v2) + " vs v1's end " + total(p1, v2), total(p2, v2) <= total(p1, v2) + 1e-6);
            // the same goal searched again ends at the same cell (ties are broken the same way)
            assertEquals(d, CadenzaTest.search(w, start, new CadenzaGoal(rs, 0, o)).getDest());
        }
    }

    /**
     * TWO_POSE (live): from a cell four blocks along the wall the ore's face spans +-1.2 degrees of yaw, under the live
     * aim noise; the break kept restarting and the ore was blacklisted. Such cells are not candidates; the cells beside
     * the ore and a few back from it are.
     */
    @Test
    public void grazingCellsAlongAWallAreNotCandidates() {
        SyntheticWorld w = CadenzaTest.floor();
        for (int x = 13; x <= 15; x++) for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 5; y++) w.set(x, y, z, CadenzaTest.stone());
        w.set(13, Y + 1, 16, CadenzaTest.ore());
        Cadenza.Result r = CadenzaTest.mine(w, 13, Y + 1, 16);
        assertEquals(Cadenza.Status.OK, r.status);
        assertTrue(r.candidates.stream().anyMatch(c -> c.x == 12 && c.z == 16));
        assertTrue(r.candidates.stream().anyMatch(c -> c.x == 9 && c.z == 16)); // straight on, at range
        for (Cadenza.Candidate c : r.candidates) {
            // the wall face is at x 13: a cell one block off it sees the face at a grazing angle beyond 2 blocks along
            assertFalse(c + " grazes the face", c.x == 12 && Math.abs(c.z - 16) >= 3);
        }
    }

    @Test
    public void steadyNeedsTheMarginBothWays() {
        SyntheticWorld w = CadenzaTest.floor();
        w.set(10, Y + 1, 10, CadenzaTest.ore());
        Cadenza.Target t = Cadenza.Target.mine(new BlockPos(10, Y + 1, 10), REACH, false);
        var bsi = PathBench.context(w).bsi;
        CadenzaRay.Hit hit = new CadenzaRay.Hit();
        net.minecraft.world.phys.Vec3 centre = new net.minecraft.world.phys.Vec3(10.5, Y + 1.5, 10.5);
        // straight on from 3 blocks: the block spans ~+-9 degrees
        assertTrue(Cadenza.steady(bsi, t, new net.minecraft.world.phys.Vec3(7.5, Y + 1.5, 10.5), centre, null, hit));
        // aimed at its very edge: half the cone misses
        assertFalse(Cadenza.steady(bsi, t, new net.minecraft.world.phys.Vec3(7.5, Y + 1.5, 10.5), new net.minecraft.world.phys.Vec3(10.0, Y + 1.5, 10.999), null, hit));
    }

    @Test
    public void occludedAndInvalidTargetsStayOutOfV2Goals() {
        SyntheticWorld w = CadenzaTest.floor();
        w.set(10, Y - 3, 10, CadenzaTest.ore()); // buried
        Cadenza.Result buried = CadenzaTest.mine(w, 10, Y - 3, 10);
        Cadenza.Result air = CadenzaTest.mine(w, 20, Y + 1, 20);
        assertEquals(Cadenza.Status.OCCLUDED, buried.status);
        assertEquals(Cadenza.Status.TARGET_INVALID, air.status);
        for (CadenzaGoal.Options o : List.of(CadenzaGoal.Options.turnAware(TURN), CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.ANY))) {
            CadenzaGoal g = new CadenzaGoal(List.of(buried, air), 0, o, new GoalBlock(25, Y, 25));
            assertFalse(g.hasCandidates());
            assertEquals(0, g.candidateCells());
            assertTrue(g.isInGoal(25, Y, 25)); // only the caller's fallback
            assertEquals(0, g.terminalCost(25, Y, 25, 24, Y, 25), 0);
        }
        // a placement with nothing to place against is invalid with v2 on too
        Cadenza.Result floating = Cadenza.candidates(PathBench.context(w), Cadenza.Target.place(new BlockPos(10, Y + 10, 10), REACH), Cadenza.Forbidden.NONE);
        assertFalse(new CadenzaGoal(List.of(floating), 0, CadenzaGoal.Options.turnAware(TURN)).hasCandidates());
    }

    @Test
    public void aWorldChangeChangesTheGoal() {
        SyntheticWorld w = CadenzaTest.floor();
        w.set(10, Y, 10, CadenzaTest.ore());
        CadenzaGoal.Options o = CadenzaGoal.Options.turnAware(TURN);
        CadenzaGoal before = new CadenzaGoal(List.of(CadenzaTest.mine(w, 10, Y, 10)), 0, o);
        w.set(10, Y, 11, CadenzaTest.stone()); // a block beside it: some cells lose sight, others change aim
        w.set(10, Y + 1, 11, CadenzaTest.stone());
        CadenzaGoal after = new CadenzaGoal(List.of(CadenzaTest.mine(w, 10, Y, 10)), 0, o);
        assertNotEquals(before, after);
        w.set(10, Y, 10, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()); // gone
        assertFalse(new CadenzaGoal(List.of(CadenzaTest.mine(w, 10, Y, 10)), 0, o).hasCandidates());
    }

    @Test
    public void batchShrinksAsTargetsAreConfirmed() {
        SyntheticWorld w = CadenzaTest.floor();
        for (int z = 0; z < 32; z++) for (int y = Y; y < Y + 5; y++) w.set(14, y, z, CadenzaTest.stone());
        for (int z = 9; z <= 11; z++) w.set(14, Y + 1, z, CadenzaTest.ore());
        CadenzaGoal.Options o = CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.ANY);
        List<Cadenza.Result> three = List.of(CadenzaTest.mine(w, 14, Y + 1, 9), CadenzaTest.mine(w, 14, Y + 1, 10), CadenzaTest.mine(w, 14, Y + 1, 11));
        CadenzaGoal g3 = new CadenzaGoal(three, 0, o);
        BetterBlockPos d = CadenzaTest.search(w, new BetterBlockPos(3, Y, 10), g3).getDest();
        assertEquals(3, g3.reachCount(d.x, d.y, d.z));
        // one confirmed broken (the world shows it gone): the caller passes the two left, and the cell still does both
        w.set(14, Y + 1, 10, CadenzaTest.stone());
        List<Cadenza.Result> two = List.of(CadenzaTest.mine(w, 14, Y + 1, 9), CadenzaTest.mine(w, 14, Y + 1, 11));
        CadenzaGoal g2 = new CadenzaGoal(two, 0, o);
        assertEquals(2, g2.reachCount(d.x, d.y, d.z));
        assertTrue(g2.terminalCost(d.x, d.y, d.z) < g3.terminalCost(d.x, d.y, d.z));
        assertNotEquals(g3, g2);
    }

    @Test
    public void batchWithoutSharedCellsIsSequential() {
        // two ores too far apart for any cell to reach both: every cell does one, and pays for the other
        SyntheticWorld w = CadenzaTest.floor();
        w.set(5, Y, 5, CadenzaTest.ore());
        w.set(25, Y, 25, CadenzaTest.ore());
        List<Cadenza.Result> rs = List.of(CadenzaTest.mine(w, 5, Y, 5), CadenzaTest.mine(w, 25, Y, 25));
        CadenzaGoal batch = new CadenzaGoal(rs, 0, CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.ANY));
        CadenzaGoal turn = new CadenzaGoal(rs, 0, CadenzaGoal.Options.turnAware(TURN));
        for (Cadenza.Result r : rs) {
            for (Cadenza.Candidate c : r.candidates) {
                assertEquals(1, batch.reachCount(c.x, c.y, c.z));
                assertTrue(batch.terminalCost(c.x, c.y, c.z) >= turn.terminalCost(c.x, c.y, c.z) + CadenzaGoal.Options.REPOSITION_TICKS - 1e-9);
            }
        }
        // the search still ends at a cell for one of them, the near one
        BetterBlockPos d = CadenzaTest.search(w, new BetterBlockPos(8, Y, 8), batch).getDest();
        assertTrue(batch.isCandidate(d.x, d.y, d.z));
        assertTrue(Math.abs(d.x - 5) <= 5 && Math.abs(d.z - 5) <= 5);
    }

    // --- goal equality --------------------------------------------------------------------------------------------

    @Test
    public void equalityFollowsTheCellData() {
        Cadenza.Result a = target(20, 20, new double[]{10, Y, 10, 1, -90, 8});
        Cadenza.Result b = target(21, 20, new double[]{11, Y, 10, 1, 90, 8});
        CadenzaGoal.Options turn = CadenzaGoal.Options.turnAware(TURN);
        // the same targets in another order (the caller sorts by distance from a moving player): equal
        assertEquals(new CadenzaGoal(List.of(a, b), 0, turn), new CadenzaGoal(List.of(b, a), 0, turn));
        assertEquals(new CadenzaGoal(List.of(a, b), 0, turn).hashCode(), new CadenzaGoal(List.of(b, a), 0, turn).hashCode());
        // rebuilt from equal results: equal (goals are rebuilt every tick)
        assertEquals(new CadenzaGoal(List.of(target(20, 20, new double[]{10, Y, 10, 1, -90, 8})), 0, turn), new CadenzaGoal(List.of(a), 0, turn));
        // a different aim at the same costs: not equal under v2
        Cadenza.Result turned = target(20, 20, new double[]{10, Y, 10, 1, 0, 8});
        assertNotEquals(new CadenzaGoal(List.of(turned), 0, turn), new CadenzaGoal(List.of(a), 0, turn));
        // other options: not equal
        assertNotEquals(new CadenzaGoal(List.of(a), 0, turn), new CadenzaGoal(List.of(a)));
        assertNotEquals(new CadenzaGoal(List.of(a), 0, turn), new CadenzaGoal(List.of(a), 0, CadenzaGoal.Options.turnAware(new Cadenza.TurnModel(10, 8))));
        // a strict batch depends on the order
        CadenzaGoal.Options strict = CadenzaGoal.Options.batched(TURN, CadenzaGoal.Ordering.STRICT);
        assertNotEquals(new CadenzaGoal(List.of(a, b), 0, strict), new CadenzaGoal(List.of(b, a), 0, strict));
    }

    @Test
    public void sessionPassesOptionsThrough() {
        SyntheticWorld w = CadenzaTest.floor();
        w.set(10, Y, 10, CadenzaTest.ore());
        CadenzaSession s = new CadenzaSession();
        CadenzaGoal.Options turn = CadenzaGoal.Options.turnAware(TURN);
        CadenzaGoal g = s.goal(PathBench.context(w), List.of(Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, false)), Cadenza.Forbidden.NONE,
                Cadenza.CellFilter.ALL, false, turn, r -> fail(), List.of());
        assertEquals(turn, g.options());
        CadenzaGoal again = s.goal(PathBench.context(w), List.of(Cadenza.Target.mine(new BlockPos(10, Y, 10), REACH, false)), Cadenza.Forbidden.NONE,
                Cadenza.CellFilter.ALL, false, turn, r -> fail(), List.of());
        assertEquals(g, again);
    }
}
