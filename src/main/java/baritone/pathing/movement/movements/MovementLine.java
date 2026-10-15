package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.LineWalk;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.SurfaceSpeed;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Walk a straight 2-by-1 instead of a grid corner. Only when the whole rectangle is open,
 * so a walled path keeps the moves it had.
 */
public class MovementLine extends Movement {

    private final int dx;
    private final int dz;

    public MovementLine(IBaritone baritone, BetterBlockPos src, int dx, int dz) {
        super(baritone, src, new BetterBlockPos(src.x + dx, src.y, src.z + dz), new BetterBlockPos[0]);
        this.dx = dx;
        this.dz = dz;
    }

    public static void cost(CalculationContext context, int x, int y, int z, int dx, int dz, MutableMoveResult res) {
        int[][] cells = LineWalk.corridor(dx, dz);
        double sum = 0;
        for (int[] c : cells) {
            int cx = x + c[0];
            int cz = z + c[1];
            BlockState feet = context.get(cx, y, cz);
            BlockState head = context.get(cx, y + 1, cz);
            BlockState floor = context.get(cx, y - 1, cz);
            if (MovementHelper.isWater(feet) || MovementHelper.isLava(feet) || MovementHelper.isLava(head)) {
                return;
            }
            // canWalkThrough answers YES for a wooden door or a gate, and for a ladder. This executor only sprints,
            // so those cells stay on MovementTraverse, which is what opens them.
            if (traverseOperates(feet) || traverseOperates(head)) {
                return;
            }
            if (!MovementHelper.canWalkThrough(context, cx, y, cz, feet) || !MovementHelper.canWalkThrough(context, cx, y + 1, cz, head)) {
                return;
            }
            if (!MovementHelper.canWalkOn(context, cx, y - 1, cz, floor)) {
                return;
            }
            boolean soul = floor.getBlock() == Blocks.SOUL_SAND;
            double m = SurfaceSpeed.one(floor.getBlock().getSpeedFactor(), floor.getBlock().getFriction(), soul);
            if (soul) {
                m *= 2;
            }
            sum += m;
        }
        boolean sprint = context.canSprint;
        res.x = x + dx;
        res.y = y;
        res.z = z + dz;
        res.cost = LineWalk.ticks(dx, dz, sprint) * (sum / cells.length);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, dx, dz, result);
        if (result.y != dest.y || result.x != dest.x || result.z != dest.z) {
            return COST_INF;
        }
        return result.cost;
    }

    /** A door, a gate, or a climb. MovementTraverse operates these; a line must not step on them. */
    static boolean traverseOperates(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof FenceGateBlock || MovementHelper.isClimbable(block);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int[] c : LineWalk.corridor(dx, dz)) {
            set.add(new BetterBlockPos(src.x + c[0], src.y, src.z + c[1]));
        }
        return set;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (ctx.playerFeet().getY() != dest.y) {
            if (ctx.playerFeet().getY() < dest.y) {
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }
        MovementHelper.moveTowards(ctx, state, dest);
        state.setInput(Input.SPRINT, true);
        return state;
    }
}
