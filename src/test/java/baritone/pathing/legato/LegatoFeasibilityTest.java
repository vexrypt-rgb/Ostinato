/*
 * Ostinato Legato: the swept-box feasibility check for straight any-angle lines.
 */
package baritone.pathing.legato;

import baritone.Baritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static baritone.pathing.legato.LegatoFeasibility.*;
import static org.junit.Assert.*;

public class LegatoFeasibilityTest {

    static final int Y = 64; // feet

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Before
    @After
    public void clean() {
        LegatoFailures.clear();
        LegatoFailures.clock = System::nanoTime;
    }

    /** A 32 by 32 stone floor at y 63, feet at 64. */
    static SyntheticWorld floor() {
        SyntheticWorld w = new SyntheticWorld(32, 32);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                w.set(x, 0, z, Blocks.BEDROCK.defaultBlockState());
                for (int y = 1; y < Y; y++) {
                    w.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        return w;
    }

    static Result eval(SyntheticWorld w, int x0, int z0, int x1, int z1) {
        return evaluate(PathBench.context(w), x0, Y, z0, x1, z1, new Result());
    }

    static void wall(SyntheticWorld w, int x, int z) {
        w.set(x, Y, z, Blocks.STONE.defaultBlockState());
        w.set(x, Y + 1, z, Blocks.STONE.defaultBlockState());
    }

    @Test
    public void settingsDefaultOff() {
        assertFalse(Baritone.settings().legato.value);
        assertTrue(Baritone.settings().legatoSmoothing.value);
        assertFalse(Baritone.settings().legatoSearch.value);
        assertFalse(Baritone.settings().legatoDebug.value);
        CalculationContext ctx = PathBench.context(floor());
        assertFalse(ctx.legatoSmooth);
        assertFalse(ctx.legatoSearch);
    }

    @Test
    public void openGroundAtAnyAngle() {
        SyntheticWorld w = floor();
        int[][] ends = {{17, 13}, {13, 17}, {3, 6}, {6, 3}, {16, 5}, {4, 16}, {15, 15}, {5, 5}, {16, 10}};
        for (int[] e : ends) {
            Result r = eval(w, 10, 10, e[0], e[1]);
            assertEquals(r.reason + " to " + e[0] + "," + e[1], FEASIBLE, r.verdict);
            double expect = Math.hypot(e[0] - 10, e[1] - 10) * perBlock(PathBench.context(w));
            assertEquals(expect, r.cost, 1e-9);
            assertTrue(r.cells >= Math.max(Math.abs(e[0] - 10), Math.abs(e[1] - 10)) + 1);
        }
    }

    @Test
    public void costMatchesTraverseUnits() {
        CalculationContext ctx = PathBench.context(floor());
        double perBlock = perBlock(ctx);
        if (ctx.canSprint) {
            assertEquals(ActionCosts.WALK_ONE_BLOCK_COST * ActionCosts.SPRINT_MULTIPLIER, perBlock, 1e-9);
        } else {
            assertEquals(ActionCosts.WALK_ONE_BLOCK_COST, perBlock, 1e-9);
        }
    }

    @Test
    public void sweptBoxNotCentreRay() {
        // the centre ray from (10.5, 10.5) to (14.5, 12.5) runs at z 11.25..11.75 across x 12..13, clear of cell
        // (12, 10); the player box with its margin reaches 0.5 to the side and does overlap that cell
        SyntheticWorld w = floor();
        wall(w, 12, 10);
        assertFalse(rayHits(10, 10, 14, 12, 12, 10));
        Result r = eval(w, 10, 10, 14, 12);
        assertEquals(INFEASIBLE, r.verdict);
        assertEquals(R_BLOCKED, r.reason);
        assertEquals(12, r.failX);
        assertEquals(10, r.failZ);
    }

    /** Does the bare centre ray pass through cell (cx, cz)? */
    static boolean rayHits(int x0, int z0, int x1, int z1, int cx, int cz) {
        return overlaps(x0 + 0.5, z0 + 0.5, x1 - x0, z1 - z0, cx, cx + 1, cz, cz + 1);
    }

    @Test
    public void headHeightObstacleBlocks() {
        SyntheticWorld w = floor();
        w.set(13, Y + 1, 10, Blocks.STONE.defaultBlockState()); // only at head height
        Result r = eval(w, 10, 10, 17, 10);
        assertEquals(INFEASIBLE, r.verdict);
        assertEquals(R_BLOCKED, r.reason);
    }

    @Test
    public void narrowCorridorAxisAlignedFits() {
        // one wide walkway with walls both sides: the box plus margin touches the walls exactly, never into them
        SyntheticWorld w = floor();
        for (int x = 8; x <= 20; x++) {
            wall(w, x, 9);
            wall(w, x, 11);
        }
        assertEquals(FEASIBLE, eval(w, 9, 10, 20, 10).verdict);
        // ...but a slanted line in it is refused
        assertEquals(INFEASIBLE, eval(w, 9, 10, 20, 11).verdict);
    }

    @Test
    public void wideCorridorTakesSlantedLine() {
        SyntheticWorld w = floor();
        for (int x = 6; x <= 22; x++) {
            wall(w, x, 7);
            wall(w, x, 13);
        }
        assertEquals(FEASIBLE, eval(w, 8, 9, 19, 11).verdict);
        // corner to corner: at each end the box sits on a cell centre, its margin just touching the wall
        assertEquals(FEASIBLE, eval(w, 8, 8, 19, 12).verdict);
        wall(w, 14, 10); // a pillar in the middle
        assertEquals(INFEASIBLE, eval(w, 8, 9, 19, 11).verdict);
        assertEquals(FEASIBLE, eval(w, 8, 8, 19, 8).verdict); // past it along the wall
    }

    @Test
    public void wallCornerIsNotCut() {
        // a wall from (14, 10) to (14, 14): a line from one side to the other cuts through it
        SyntheticWorld w = floor();
        for (int i = 0; i <= 4; i++) {
            wall(w, 14, 10 + i);
        }
        assertEquals(INFEASIBLE, eval(w, 12, 12, 16, 9).verdict);
        assertEquals(FEASIBLE, eval(w, 12, 9, 16, 9).verdict); // past the wall's end, the margin exactly touching it
        assertEquals(INFEASIBLE, eval(w, 12, 9, 16, 10).verdict); // rising into the wall's end
    }

    @Test
    public void ledgesGapsAndHoles() {
        SyntheticWorld w = floor();
        w.set(14, Y - 1, 10, Blocks.AIR.defaultBlockState()); // a hole on the line
        Result r = eval(w, 10, 10, 18, 10);
        assertEquals(INFEASIBLE, r.verdict);
        assertEquals(R_NO_FLOOR, r.reason);
        SyntheticWorld edge = floor();
        for (int x = 0; x < 32; x++) {
            for (int z = 12; z < 32; z++) {
                edge.set(x, Y - 1, z, Blocks.AIR.defaultBlockState()); // a ledge: no floor from z 12
            }
        }
        assertEquals(FEASIBLE, eval(edge, 5, 11, 16, 11).verdict); // along the edge
        assertEquals(INFEASIBLE, eval(edge, 5, 11, 15, 13).verdict); // off it
        assertEquals(FEASIBLE, eval(edge, 5, 10, 15, 11).verdict); // ends on the edge cell's centre: margin touches the lip
        edge.set(9, Y - 1, 11, Blocks.AIR.defaultBlockState()); // a notch in the edge beside the ray, under the margin
        assertFalse(rayHits(5, 10, 15, 11, 9, 11));
        assertEquals(INFEASIBLE, eval(edge, 5, 10, 15, 11).verdict);
    }

    @Test
    public void stepUpsSlabsAndStairsAreRefused() {
        SyntheticWorld w = floor();
        w.set(14, Y, 10, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()); // a step up on the line
        assertEquals(INFEASIBLE, eval(w, 10, 10, 18, 10).verdict);
        SyntheticWorld floorSlab = floor();
        floorSlab.set(14, Y - 1, 10, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()); // a half step down in the floor
        Result r = eval(floorSlab, 10, 10, 18, 10);
        assertEquals(UNSUPPORTED, r.verdict);
        assertEquals(R_FLOOR, r.reason);
        SyntheticWorld stair = floor();
        stair.set(14, Y - 1, 10, Blocks.OAK_STAIRS.defaultBlockState());
        assertEquals(UNSUPPORTED, eval(stair, 10, 10, 18, 10).verdict);
    }

    @Test
    public void oddFloorsAreUnsupported() {
        for (BlockState s : new BlockState[]{Blocks.ICE.defaultBlockState(), Blocks.PACKED_ICE.defaultBlockState(),
                Blocks.SOUL_SAND.defaultBlockState(), Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState()}) {
            SyntheticWorld w = floor();
            w.set(13, Y - 1, 11, s);
            Result r = eval(w, 10, 10, 16, 12);
            assertNotEquals(s.toString(), FEASIBLE, r.verdict);
        }
    }

    @Test
    public void hazards() {
        SyntheticWorld magma = floor();
        magma.set(13, Y - 1, 10, Blocks.MAGMA_BLOCK.defaultBlockState());
        assertEquals(R_HAZARD, eval(magma, 10, 10, 18, 10).reason);
        SyntheticWorld lava = floor();
        lava.set(13, Y - 1, 10, Blocks.LAVA.defaultBlockState());
        assertEquals(INFEASIBLE, eval(lava, 10, 10, 18, 10).verdict);
        SyntheticWorld lavaBeside = floor();
        lavaBeside.set(13, Y, 11, Blocks.LAVA.defaultBlockState()); // in the margin of a slanted line
        assertEquals(INFEASIBLE, eval(lavaBeside, 10, 10, 18, 12).verdict);
        SyntheticWorld cactus = floor();
        cactus.set(13, Y, 10, Blocks.CACTUS.defaultBlockState());
        assertEquals(INFEASIBLE, eval(cactus, 10, 10, 18, 10).verdict);
        SyntheticWorld fire = floor();
        fire.set(13, Y, 10, Blocks.FIRE.defaultBlockState());
        assertEquals(R_HAZARD, eval(fire, 10, 10, 18, 10).reason);
        SyntheticWorld web = floor();
        web.set(13, Y + 1, 10, Blocks.COBWEB.defaultBlockState());
        assertEquals(INFEASIBLE, eval(web, 10, 10, 18, 10).verdict);
    }

    @Test
    public void fluidsDoorsAndClimbsAreUnsupported() {
        SyntheticWorld water = floor();
        water.set(13, Y, 10, Blocks.WATER.defaultBlockState());
        Result r = eval(water, 10, 10, 18, 10);
        assertEquals(UNSUPPORTED, r.verdict);
        assertEquals(R_FLUID, r.reason);
        SyntheticWorld door = floor();
        door.set(13, Y, 10, Blocks.OAK_DOOR.defaultBlockState());
        assertEquals(R_OPERATED, eval(door, 10, 10, 18, 10).reason);
        SyntheticWorld gate = floor();
        gate.set(13, Y, 10, Blocks.OAK_FENCE_GATE.defaultBlockState());
        assertEquals(R_OPERATED, eval(gate, 10, 10, 18, 10).reason);
        SyntheticWorld ladder = floor();
        ladder.set(13, Y, 10, Blocks.LADDER.defaultBlockState());
        assertEquals(R_OPERATED, eval(ladder, 10, 10, 18, 10).reason);
    }

    @Test
    public void passableDecorationIsFine() {
        SyntheticWorld w = floor();
        w.set(13, Y, 10, Blocks.SHORT_GRASS.defaultBlockState());
        w.set(15, Y, 11, Blocks.DANDELION.defaultBlockState());
        assertEquals(FEASIBLE, eval(w, 10, 10, 18, 11).verdict);
    }

    @Test
    public void carpetAndSnowLayerOnTheLineAreRefused() {
        SyntheticWorld carpet = floor();
        carpet.set(13, Y, 10, Blocks.MOSS_CARPET.defaultBlockState());
        assertNotEquals(FEASIBLE, eval(carpet, 10, 10, 18, 10).verdict);
        SyntheticWorld snow = floor();
        snow.set(13, Y, 10, Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, 3));
        assertNotEquals(FEASIBLE, eval(snow, 10, 10, 18, 10).verdict);
        SyntheticWorld dusting = floor();
        dusting.set(13, Y, 10, Blocks.SNOW.defaultBlockState()); // one layer has no collision: walked through as air
        assertEquals(FEASIBLE, eval(dusting, 10, 10, 18, 10).verdict);
    }

    @Test
    public void heightChangeSpanAndSameCell() {
        SyntheticWorld w = floor();
        CalculationContext ctx = PathBench.context(w);
        assertEquals(R_HEIGHT, evaluate(ctx, 10, Y, 10, 14, Y + 1, 12, new Result()).reason);
        assertEquals(UNSUPPORTED, evaluate(ctx, 10, Y, 10, 14, Y - 1, 12, new Result()).verdict);
        assertEquals(R_SPAN, eval(w, 2, 2, 2 + MAX_SPAN + 1, 4).reason);
        assertEquals(FEASIBLE, eval(w, 2, 2, 2 + MAX_SPAN, 4).verdict);
        assertEquals(R_SAME, eval(w, 5, 5, 5, 5).reason);
    }

    @Test
    public void unloadedTerrainIsNeverOpen() {
        SyntheticWorld w = floor();
        w.knownMaxX = 15;
        Result r = eval(w, 10, 10, 18, 12);
        assertEquals(UNKNOWN, r.verdict);
        assertEquals(R_UNLOADED, r.reason);
        assertEquals(FEASIBLE, eval(w, 6, 10, 14, 12).verdict); // stays inside what is known, margin included
        assertEquals(UNKNOWN, eval(w, 6, 10, 15, 12).verdict);
    }

    @Test
    public void worldChangeIsSeenOnTheNextCheck() {
        SyntheticWorld w = floor();
        CalculationContext ctx = PathBench.context(w);
        Result r = new Result();
        assertEquals(FEASIBLE, evaluate(ctx, 10, Y, 10, 18, 13, r).verdict);
        w.set(14, Y, 11, Blocks.STONE.defaultBlockState());
        assertEquals(INFEASIBLE, evaluate(ctx, 10, Y, 10, 18, 13, r).verdict);
        w.set(14, Y, 11, Blocks.AIR.defaultBlockState());
        assertEquals(FEASIBLE, evaluate(ctx, 10, Y, 10, 18, 13, r).verdict);
    }

    @Test
    public void failedLinesAreNotRetriedUntilTheyExpire() {
        long[] now = {1_000_000_000L};
        LegatoFailures.clock = () -> now[0];
        SyntheticWorld w = floor();
        assertEquals(FEASIBLE, eval(w, 10, 10, 18, 13).verdict);
        LegatoFailures.record(10, Y, 10, 18, 13, 12, 11);
        assertEquals(R_FAILED, eval(w, 10, 10, 18, 13).reason);
        assertEquals(R_FAILED, eval(w, 18, 13, 10, 10).reason); // either way along it
        assertEquals(R_FAILED, eval(w, 9, 12, 16, 9).reason); // another line through the cell where it failed
        assertEquals(FEASIBLE, eval(w, 20, 20, 26, 24).verdict); // elsewhere is untouched
        now[0] += LegatoFailures.TTL_NANOS + 1;
        assertEquals(FEASIBLE, eval(w, 10, 10, 18, 13).verdict);
        assertEquals(FEASIBLE, eval(w, 9, 12, 16, 9).verdict);
    }

    @Test
    public void slabTestTouchingIsNotOverlap() {
        assertFalse(overlaps(0.5, 0.5, 4, 0, -1, 0.5, 0, 1)); // starts exactly on the box's edge, heading away
        assertTrue(overlaps(0.5, 0.5, 4, 0, 2, 3, 0, 1));
        assertFalse(overlaps(0.5, 0.5, 4, 0, 2, 3, 1, 2)); // misses it to the side
        assertFalse(overlaps(0.5, 1, 4, 0, 2, 3, 1, 2)); // runs along the box's edge
        assertTrue(overlaps(0.5, 0.5, 4, 4, 2, 3, 2, 3));
        assertFalse(overlaps(0.5, 0.5, 4, 4, 3, 4, 0, 1));
    }

    @Test
    public void validCellsCoverTheSweep() {
        java.util.Set<Long> cells = new java.util.HashSet<>();
        forEachCell(10, 10, 18, 13, (x, z) -> cells.add(((long) x << 32) | (z & 0xffffffffL)));
        assertTrue(cells.contains(((long) 10 << 32) | 10));
        assertTrue(cells.contains(((long) 18 << 32) | 13));
        Result r = eval(floor(), 10, 10, 18, 13);
        assertEquals(cells.size(), r.cells);
    }

    @Test
    public void evaluationAllocatesNothing() {
        SyntheticWorld w = floor();
        CalculationContext ctx = PathBench.context(w);
        Result r = new Result();
        for (int i = 0; i < 2000; i++) {
            evaluate(ctx, 10, Y, 10, 10 + (i % 12) + 1, 10 + (i % 7), r);
        }
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long a0 = mx.getThreadAllocatedBytes(tid);
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            evaluate(ctx, 10, Y, 10, 10 + (i % 12) + 1, 10 + (i % 7), r);
        }
        long bytes = mx.getThreadAllocatedBytes(tid) - a0;
        System.out.println("LEGATO evaluate allocation: " + bytes + " bytes over " + n + " checks");
        assertTrue("allocated " + bytes + " bytes over " + n + " checks", bytes < 64 * 1024);
    }
}
