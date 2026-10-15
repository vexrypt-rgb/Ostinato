package baritone.pathing;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.BidirectionalSearch;
import baritone.pathing.calc.MovingGoal;
import baritone.pathing.calc.SearchProfile;
import baritone.pathing.movement.DoorPass;
import baritone.pathing.movement.FallBudget;
import baritone.pathing.movement.JumpFailures;
import baritone.pathing.movement.LineWalk;
import baritone.pathing.movement.Moves;
import baritone.pathing.movement.SurfaceSpeed;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PathingQueueTest {

    @Test
    public void failedJumpCostsMoreThenFades() {
        assertEquals(1, JumpFailures.curve(0, 0, JumpFailures.WINDOW_MS), 0);
        assertEquals(4, JumpFailures.curve(1, 0, JumpFailures.WINDOW_MS), 1e-9);
        assertEquals(8, JumpFailures.curve(2, 0, JumpFailures.WINDOW_MS), 1e-9);
        assertEquals(1, JumpFailures.curve(3, JumpFailures.WINDOW_MS, JumpFailures.WINDOW_MS), 0);
        assertEquals(2.5, JumpFailures.curve(1, JumpFailures.WINDOW_MS / 2, JumpFailures.WINDOW_MS), 1e-9);
        JumpFailures.forget();
        assertEquals(1, JumpFailures.multiplier(7, 1_000), 0);
        JumpFailures.mark(7, 1_000);
        assertEquals(4, JumpFailures.multiplier(7, 1_000), 1e-6);
        JumpFailures.mark(7, 1_500);
        assertEquals(8, JumpFailures.multiplier(7, 1_500), 1e-6);
        assertEquals(1, JumpFailures.multiplier(7, 1_500 + JumpFailures.WINDOW_MS), 0);
        JumpFailures.forget();
    }

    @Test
    public void movingGoalKeepsAPathThatStillApproaches() {
        assertEquals(MovingGoal.Action.KEEP, MovingGoal.decide(true, true, 10, 8));
        assertEquals(MovingGoal.Action.KEEP, MovingGoal.decide(false, false, 10, 12));
        assertEquals(MovingGoal.Action.RETARGET, MovingGoal.decide(true, false, 10, 8));
        assertEquals(MovingGoal.Action.RETARGET, MovingGoal.decide(true, false, 10, 10));
        assertEquals(MovingGoal.Action.CANCEL, MovingGoal.decide(true, false, 10, 14));
        assertEquals(10, MovingGoal.horizonIndex(21, 0, 4, 16));
        assertEquals(18, MovingGoal.horizonIndex(21, 18, 4, 16));
    }

    @Test
    public void bidirectionalMatchesTheUniquePath() {
        BidirectionalSearch.Graph g = new BidirectionalSearch.Graph() {
            final int[][] to = {{1, 2}, {0, 3}, {0, 3}, {1, 2}};
            final double[][] cost = {{1, 5}, {1, 1}, {5, 1}, {1, 1}};

            public int nodeCount() { return 4; }
            public int start() { return 0; }
            public int goal() { return 3; }
            public List<BidirectionalSearch.Edge> out(int node) {
                List<BidirectionalSearch.Edge> edges = new ArrayList<>();
                for (int i = 0; i < to[node].length; i++) {
                    edges.add(new BidirectionalSearch.Edge(to[node][i], cost[node][i]));
                }
                return edges;
            }
            public double heuristic(int from, int toNode) { return 0; }
        };
        int[] forward = BidirectionalSearch.forward(g);
        int[] both = BidirectionalSearch.bidirectional(g);
        assertArrayEquals(new int[]{0, 1, 3}, forward);
        assertArrayEquals(forward, both);
    }

    @Test
    public void surfaceSpeedLeavesStoneAlone() {
        assertEquals(1, SurfaceSpeed.one(1, 0.6f, false), 1e-9);
        assertEquals(1, SurfaceSpeed.pair(1, 0.6f, false, 1, 0.6f, false), 1e-9);
        assertEquals(2.5, SurfaceSpeed.one(0.4f, 0.6f, false), 1e-6);
        assertEquals(1, SurfaceSpeed.one(0.4f, 0.6f, true), 1e-9);
        double ice = SurfaceSpeed.one(1, 0.98f, false);
        assertTrue(ice < 0.3);
        assertTrue(ice > 0.2);
    }

    @Test
    public void doorOpenerHasToBeOnThisSideAndStayOpen() {
        assertTrue(DoorPass.fromThisSide(1, 0, 0, 0));
        assertTrue(DoorPass.fromThisSide(1, 0, -1, 0));
        assertFalse(DoorPass.fromThisSide(1, 0, 1, 0));
        assertTrue(DoorPass.fromThisSide(1, 0, 0, 1));
        assertTrue(DoorPass.price(DoorPass.Kind.WOOD_BUTTON, 4) < ActionCosts.COST_INF);
        assertTrue(DoorPass.price(DoorPass.Kind.STONE_BUTTON, 4) < ActionCosts.COST_INF);
        assertEquals(ActionCosts.COST_INF, DoorPass.price(DoorPass.Kind.NONE, 4), 0);
        assertEquals(1, DoorPass.price(DoorPass.Kind.PLATE, 4), 0);
        assertEquals(1, DoorPass.price(DoorPass.Kind.LEVER, 4), 0);
    }

    @Test
    public void fallBudgetKeepsAFloor() {
        assertEquals(6, FallBudget.maxBlocks(20, FallBudget.FLOOR, 3, FallBudget.CAP));
        assertEquals(3, FallBudget.maxBlocks(8, FallBudget.FLOOR, 3, FallBudget.CAP));
        assertEquals(4, FallBudget.maxBlocks(11, 10, 3, 6));
    }

    @Test
    public void lineWalkIsShorterThanTheCorner() {
        double line = LineWalk.ticks(2, 1, true);
        double corner = 3 * ActionCosts.SPRINT_ONE_BLOCK_COST;
        assertTrue(line < corner);
        int[][] cells = LineWalk.corridor(2, 1);
        assertEquals(5, cells.length);
        assertTrue(Arrays.stream(cells).anyMatch(c -> c[0] == 2 && c[1] == 1));
        assertFalse(Arrays.stream(cells).anyMatch(c -> c[0] == 0 && c[1] == 0));
    }

    @Test
    public void seedHookIsOffUntilRegistered() {
        SeedPlan.clear();
        assertFalse(SeedPlan.active());
        assertFalse(SeedPlan.covers(0, 0));
        assertEquals(null, SeedPlan.predict(0, 64, 0));
        SeedPlan.use(99L, new SeedPlan.Predictor() {
            public boolean covers(int x, int z) { return x == 3 && z == 4; }
            public net.minecraft.world.level.block.state.BlockState block(int x, int y, int z) { return null; }
        });
        assertTrue(SeedPlan.active());
        assertEquals(99L, SeedPlan.seed());
        assertTrue(SeedPlan.covers(3, 4));
        assertFalse(SeedPlan.covers(0, 0));
        SeedPlan.clear();
        assertFalse(SeedPlan.covers(3, 4));
    }

    @Test
    public void moveFamilyIsCached() {
        assertEquals("TRAVERSE", Moves.TRAVERSE_NORTH.family());
        assertEquals("TRAVERSE", Moves.TRAVERSE_SOUTH.family());
        assertEquals("LINE", Moves.LINE_EAST_NORTH.family());
        assertSame(Moves.TRAVERSE_NORTH.family(), Moves.TRAVERSE_NORTH.family());
        assertSame(Moves.all(), Moves.all());
    }

    @Test
    public void calibrationWaitsAndNeverGoesBelowOne() {
        CostCalibration.reset();
        assertEquals(1, CostCalibration.scale("TRAVERSE"), 0);
        for (int i = 0; i < CostCalibration.MIN_SAMPLES; i++) {
            CostCalibration.record("TRAVERSE", 10, 5);
        }
        assertEquals(1, CostCalibration.scale("TRAVERSE"), 0);
        CostCalibration.reset();
        for (int i = 0; i < CostCalibration.MIN_SAMPLES; i++) {
            CostCalibration.record("TRAVERSE", 10, 20);
        }
        assertEquals(1.5, CostCalibration.scale("TRAVERSE"), 1e-9);
        CostCalibration.reset();
    }

    @Test
    public void routeReuseDropsWhenTheBlocksOrTheCostsChange() {
        RouteReuse.clear();
        JumpFailures.forget();
        CostCalibration.reset();
        Goal goal = new GoalBlock(2, 0, 0);
        List<BetterBlockPos> pos = Arrays.asList(
                new BetterBlockPos(0, 0, 0),
                new BetterBlockPos(1, 0, 0),
                new BetterBlockPos(2, 0, 0));
        Map<Long, Integer> world = new HashMap<>();
        RouteReuse.Blocks blocks = (x, y, z) -> world.getOrDefault(cell(x, y, z), -1);
        for (BetterBlockPos p : pos) {
            world.put(cell(p.x, p.y - 1, p.z), 1);
            world.put(cell(p.x, p.y, p.z), 2);
            world.put(cell(p.x, p.y + 1, p.z), 2);
        }
        long stamp = RouteReuse.stamp(0);
        RouteReuse.remember(goal, pos, blocks, stamp, null);
        assertEquals(0, RouteReuse.find(goal, 0, 0, 0, blocks, stamp));
        assertEquals(1, RouteReuse.find(goal, 1, 0, 0, blocks, stamp));
        assertEquals(-1, RouteReuse.find(goal, 2, 0, 0, blocks, stamp));
        assertEquals(-1, RouteReuse.find(new GoalBlock(9, 0, 0), 0, 0, 0, blocks, stamp));
        world.put(cell(0, 0, 0), 9);
        assertEquals(1, RouteReuse.find(goal, 1, 0, 0, blocks, stamp));
        assertEquals(-1, RouteReuse.find(goal, 0, 0, 0, blocks, stamp));
        world.put(cell(1, 0, 0), 9);
        assertEquals(-1, RouteReuse.find(goal, 1, 0, 0, blocks, stamp));
        assertEquals(stamp, RouteReuse.stamp(0));
        JumpFailures.mark(1L, 1_000);
        assertEquals(Long.MIN_VALUE, RouteReuse.stamp(1_000));
        JumpFailures.forget();
        for (int i = 0; i < CostCalibration.MIN_SAMPLES; i++) {
            CostCalibration.record("TRAVERSE", 10, 20);
        }
        assertTrue(RouteReuse.stamp(0) != stamp);
        CostCalibration.reset();
        RouteReuse.clear();
    }

    private static long cell(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) (y + 2048) & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    @Test
    public void searchProfileNamesTheCounters() {
        SearchProfile profile = new SearchProfile();
        profile.nodes = 4;
        profile.movements = 10;
        profile.rejected = 6;
        profile.emptyChunks = 1;
        profile.favored = 2;
        String line = profile.line();
        assertTrue(line.contains("nodes=4"));
        assertTrue(line.contains("rejected=6"));
        assertTrue(line.contains("favored=2"));
    }
}
