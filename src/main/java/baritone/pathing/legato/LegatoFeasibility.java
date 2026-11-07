/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.legato;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ostinato Legato: can the player walk a straight line, at any angle, from the centre of one cell to the centre of
 * another at the same feet height?
 * <p>
 * The whole player box (half width {@link #PLAYER_HALF}) plus a safety {@link #MARGIN} is swept along the line, not
 * just the centre ray: every cell that swept square overlaps must have open feet and head space with no collision
 * shape, no fluid and nothing that hurts, over a full, plain floor (ordinary friction, speed and jump: no ice, slime,
 * soul sand, honey, slab, stair, carpet or snow layer). Terrain the client has not loaded is never read as open: the
 * answer is then {@link #UNKNOWN}. Flat only, at most {@link #MAX_SPAN} blocks, so there is no step-up, drop, jump,
 * mining, placing, climbing, swimming or door on a line by construction.
 * <p>
 * Pure and allocation-free: the caller owns the {@link Result}. Runs on the planning thread over the
 * {@link CalculationContext} snapshot, and again from {@code MovementStraight.calculateCost} on the client thread
 * each tick, which is what invalidates a line when the terrain under it changes.
 */
public final class LegatoFeasibility {

    public static final int FEASIBLE = 0;
    /** Something along the line blocks or hurts: a wall, a hole, a hazard, or a line that failed recently. */
    public static final int INFEASIBLE = 1;
    /** A line here needs something v1 does not do: a height change, a long span, a fluid, a door, an odd floor. */
    public static final int UNSUPPORTED = 2;
    /** Part of the line is in terrain the client has not loaded. */
    public static final int UNKNOWN = 3;

    /** Half width of the player's collision box. */
    public static final double PLAYER_HALF = 0.3;
    /** Clearance kept on each side of the box, for the controller's steering error and the server's rounding. */
    public static final double MARGIN = 0.2;
    public static final double HALF = PLAYER_HALF + MARGIN;
    /** Longest line, in blocks along either axis: about 43 ticks at a sprint, well inside the driver's patience. */
    public static final int MAX_SPAN = 12;

    public static final String R_OK = "ok";
    public static final String R_SAME = "same cell";
    public static final String R_HEIGHT = "height change";
    public static final String R_SPAN = "too long";
    public static final String R_UNLOADED = "unloaded";
    public static final String R_BORDER = "world border";
    public static final String R_BLOCKED = "blocked";
    public static final String R_HAZARD = "hazard";
    public static final String R_FLUID = "fluid";
    public static final String R_OPERATED = "door/gate/climbable";
    public static final String R_NO_FLOOR = "no floor";
    public static final String R_FLOOR = "unsupported floor";
    public static final String R_FAILED = "failed recently";

    private LegatoFeasibility() {}

    /** Mutable answer, reused by the caller. */
    public static final class Result {
        public int verdict;
        /** Ticks to walk the line, in A* cost units; {@link ActionCosts#COST_INF} unless feasible. */
        public double cost;
        public String reason;
        /** Cells the swept box covers (counted while checking). */
        public int cells;
        /** The first cell that refused the line. */
        public int failX;
        public int failZ;

        void set(int verdict, String reason) {
            this.verdict = verdict;
            this.reason = reason;
            this.cost = ActionCosts.COST_INF;
        }

        public boolean feasible() {
            return verdict == FEASIBLE;
        }
    }

    /** Ticks per block for a line: the same units as MovementTraverse on a plain floor. */
    public static double perBlock(CalculationContext context) {
        return context.canSprint ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST;
    }

    public static double cost(CalculationContext context, int x0, int z0, int x1, int z1) {
        return Math.hypot(x1 - x0, z1 - z0) * perBlock(context);
    }

    /**
     * Checks the straight line from the centre of (x0, y, z0) to the centre of (x1, y, z1), with the player's feet at y.
     */
    public static Result evaluate(CalculationContext context, int x0, int y, int z0, int x1, int z1, Result out) {
        long start = System.nanoTime();
        evaluate0(context, x0, y, z0, x1, z1, out);
        LegatoStats.evaluated(out.verdict, System.nanoTime() - start);
        return out;
    }

    /** As {@link #evaluate} for two cells that may differ in height. */
    public static Result evaluate(CalculationContext context, int x0, int y0, int z0, int x1, int y1, int z1, Result out) {
        if (y0 != y1) {
            out.cells = 0;
            out.set(UNSUPPORTED, R_HEIGHT);
            LegatoStats.evaluated(UNSUPPORTED, 0);
            return out;
        }
        return evaluate(context, x0, y0, z0, x1, z1, out);
    }

    private static void evaluate0(CalculationContext context, int x0, int y, int z0, int x1, int z1, Result out) {
        out.cells = 0;
        out.failX = x0;
        out.failZ = z0;
        int dx = x1 - x0;
        int dz = z1 - z0;
        if (dx == 0 && dz == 0) {
            out.set(UNSUPPORTED, R_SAME);
            return;
        }
        if (Math.abs(dx) > MAX_SPAN || Math.abs(dz) > MAX_SPAN) {
            out.set(UNSUPPORTED, R_SPAN);
            return;
        }
        if (y - 1 < context.worldMinY || y + 1 > context.worldMaxY) {
            out.set(UNSUPPORTED, R_HEIGHT);
            return;
        }
        if (LegatoFailures.pairFailed(x0, y, z0, x1, z1)) {
            out.set(INFEASIBLE, R_FAILED);
            return;
        }
        boolean anyFailedCells = LegatoFailures.anyCells();
        double ax = x0 + 0.5;
        double az = z0 + 0.5;
        int minX = Math.min(x0, x1) - 1;
        int maxX = Math.max(x0, x1) + 1;
        int minZ = Math.min(z0, z1) - 1;
        int maxZ = Math.max(z0, z1) + 1;
        // the first refusing cell decides the answer: whatever lies beyond it cannot make the line walkable
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!overlaps(ax, az, dx, dz, cx - HALF, cx + 1 + HALF, cz - HALF, cz + 1 + HALF)) {
                    continue;
                }
                out.cells++;
                out.failX = cx;
                out.failZ = cz;
                if (!context.bsi.isKnown(cx, cz)) {
                    out.set(UNKNOWN, R_UNLOADED);
                    return;
                }
                if (!context.worldBorder.entirelyContains(cx, cz)) {
                    out.set(INFEASIBLE, R_BORDER);
                    return;
                }
                if (anyFailedCells && LegatoFailures.cellFailed(cx, y, cz)) {
                    out.set(INFEASIBLE, R_FAILED);
                    return;
                }
                int verdict = cell(context, cx, y, cz, out);
                if (verdict != FEASIBLE) {
                    return;
                }
            }
        }
        out.verdict = FEASIBLE;
        out.reason = R_OK;
        out.cost = Math.hypot(dx, dz) * perBlock(context);
    }

    /**
     * Ostinato kinematic MovementLine: the evaluator's per-cell rules for one cell of a walk at feet y, without the
     * sweep: loaded, inside the border, open feet and head, plain full floor. FEASIBLE or the refusing verdict, with
     * {@code out}'s reason set.
     */
    public static int plainCell(CalculationContext context, int x, int y, int z, Result out) {
        if (!context.bsi.isKnown(x, z)) {
            out.set(UNKNOWN, R_UNLOADED);
            return UNKNOWN;
        }
        if (!context.worldBorder.entirelyContains(x, z)) {
            out.set(INFEASIBLE, R_BORDER);
            return INFEASIBLE;
        }
        out.verdict = FEASIBLE;
        out.reason = R_OK;
        return cell(context, x, y, z, out);
    }

    /** Checks one swept cell; on refusal sets {@code out} and returns its verdict. */
    private static int cell(CalculationContext context, int x, int y, int z, Result out) {
        BlockState feet = context.get(x, y, z);
        BlockState head = context.get(x, y + 1, z);
        BlockState floor = context.get(x, y - 1, z);
        int v = space(context, x, y, z, feet, out);
        if (v != FEASIBLE) {
            return v;
        }
        v = space(context, x, y + 1, z, head, out);
        if (v != FEASIBLE) {
            return v;
        }
        return floor(context, x, y - 1, z, floor, out);
    }

    private static int space(CalculationContext context, int x, int y, int z, BlockState state, Result out) {
        Block block = state.getBlock();
        if (MovementHelper.isLava(state)) {
            out.set(INFEASIBLE, R_HAZARD);
            return INFEASIBLE;
        }
        if (!state.getFluidState().isEmpty()) {
            out.set(UNSUPPORTED, R_FLUID);
            return UNSUPPORTED;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock || MovementHelper.isClimbable(block)
                || block == Blocks.POWDER_SNOW || block == Blocks.SCAFFOLDING) {
            out.set(UNSUPPORTED, R_OPERATED);
            return UNSUPPORTED;
        }
        if (MovementHelper.avoidWalkingInto(state)) {
            out.set(INFEASIBLE, R_HAZARD);
            return INFEASIBLE;
        }
        if (top(state) >= 0 || !MovementHelper.canWalkThrough(context, x, y, z, state)) {
            out.set(INFEASIBLE, R_BLOCKED);
            return INFEASIBLE;
        }
        return FEASIBLE;
    }

    private static int floor(CalculationContext context, int x, int y, int z, BlockState state, Result out) {
        Block block = state.getBlock();
        if (MovementHelper.isLava(state) || (block == Blocks.MAGMA_BLOCK && !context.allowWalkOnMagmaBlocks)) {
            out.set(INFEASIBLE, R_HAZARD);
            return INFEASIBLE;
        }
        if (!state.getFluidState().isEmpty()) {
            out.set(UNSUPPORTED, R_FLUID);
            return UNSUPPORTED;
        }
        double top = top(state);
        if (top < 0) {
            out.set(INFEASIBLE, R_NO_FLOOR);
            return INFEASIBLE;
        }
        if (top != 1.0 || !MovementHelper.isBlockNormalCube(state)
                || block.getFriction() != 0.6f || block.getSpeedFactor() != 1.0f || block.getJumpFactor() != 1.0f) {
            out.set(UNSUPPORTED, R_FLOOR);
            return UNSUPPORTED;
        }
        if (!MovementHelper.canWalkOn(context, x, y, z, state)) {
            out.set(INFEASIBLE, R_NO_FLOOR);
            return INFEASIBLE;
        }
        return FEASIBLE;
    }

    /** {@link MovementHelper#collisionTop} per block state, filled on first use: reading a shape's height allocates. */
    private static final class TopMemo {
        static final float[] TOP = new float[Block.BLOCK_STATE_REGISTRY.size()];

        static {
            java.util.Arrays.fill(TOP, Float.NaN);
        }

        private TopMemo() {}
    }

    static double top(BlockState state) {
        float[] memo = TopMemo.TOP;
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0 || id >= memo.length) {
            return MovementHelper.collisionTop(state);
        }
        float v = memo[id];
        if (Float.isNaN(v)) {
            v = (float) MovementHelper.collisionTop(state); // the same answer from any thread, so a race is harmless
            memo[id] = v;
        }
        return v;
    }

    /**
     * Does the segment from (ax, az) along (dx, dz) pass through the inside of the box (touching an edge does not
     * count)? Slab test.
     */
    static boolean overlaps(double ax, double az, double dx, double dz, double minX, double maxX, double minZ, double maxZ) {
        double t0 = 0;
        double t1 = 1;
        if (dx == 0) {
            if (ax <= minX || ax >= maxX) {
                return false;
            }
        } else {
            double a = (minX - ax) / dx;
            double b = (maxX - ax) / dx;
            if (a > b) {
                double t = a;
                a = b;
                b = t;
            }
            t0 = Math.max(t0, a);
            t1 = Math.min(t1, b);
        }
        if (dz == 0) {
            if (az <= minZ || az >= maxZ) {
                return false;
            }
        } else {
            double a = (minZ - az) / dz;
            double b = (maxZ - az) / dz;
            if (a > b) {
                double t = a;
                a = b;
                b = t;
            }
            t0 = Math.max(t0, a);
            t1 = Math.min(t1, b);
        }
        return t1 - t0 > 1e-9;
    }

    /** Calls {@code sink} for each cell the swept box covers (for a movement's valid positions). */
    public static void forEachCell(int x0, int z0, int x1, int z1, CellSink sink) {
        int dx = x1 - x0;
        int dz = z1 - z0;
        double ax = x0 + 0.5;
        double az = z0 + 0.5;
        for (int cx = Math.min(x0, x1) - 1; cx <= Math.max(x0, x1) + 1; cx++) {
            for (int cz = Math.min(z0, z1) - 1; cz <= Math.max(z0, z1) + 1; cz++) {
                if (overlaps(ax, az, dx, dz, cx - HALF, cx + 1 + HALF, cz - HALF, cz + 1 + HALF)) {
                    sink.cell(cx, cz);
                }
            }
        }
    }

    @FunctionalInterface
    public interface CellSink {
        void cell(int x, int z);
    }
}
