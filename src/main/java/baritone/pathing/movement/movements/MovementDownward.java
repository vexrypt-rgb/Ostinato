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

package baritone.pathing.movement.movements;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.pathing.MutableMoveResult;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class MovementDownward extends Movement {

    private int numTicks = 0;

    public MovementDownward(IBaritone baritone, BetterBlockPos start, BetterBlockPos end) {
        super(baritone, start, end, new BetterBlockPos[]{end});
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, res);
        return res.y == dest.y ? res.cost : COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        if (dest.y >= src.y - 1) {
            return ImmutableSet.of(src, dest);
        }
        ImmutableSet.Builder<BetterBlockPos> column = ImmutableSet.builder();
        for (int y = src.y; y >= dest.y; y--) {
            column.add(new BetterBlockPos(src.x, y, src.z)); // a fall passes every cell of the column
        }
        return column.build();
    }

    /**
     * Ostinato: one block down, or let go of a ladder or vine and fall straight down the column. A ladder that ends above
     * a drop (nothing to stand on two below the bottom rung) has no other way off it, and a straight drop can't steer.
     */
    public static void cost(CalculationContext context, int x, int y, int z, MutableMoveResult res) {
        res.x = x;
        res.y = y - 1;
        res.z = z;
        res.cost = cost(context, x, y, z);
        if (res.cost < COST_INF || !context.allowDownward) {
            return;
        }
        if (!MovementHelper.isClimbable(context.get(x, y, z).getBlock()) && !MovementHelper.isClimbable(context.get(x, y - 1, z).getBlock())) {
            return;
        }
        for (int ly = y - 1; y - ly <= context.maxFallHeightNoWater; ly--) {
            BlockState here = context.get(x, ly, z);
            if (!MovementHelper.isClimbable(here.getBlock()) && !MovementHelper.canWalkThrough(context, x, ly, z, here)) {
                return;
            }
            if (ly < y - 1 && MovementHelper.canWalkOn(context, x, ly - 1, z)) {
                res.y = ly;
                res.cost = LADDER_DOWN_ONE_COST + FALL_N_BLOCKS_COST[y - ly];
                return;
            }
        }
    }

    public static double cost(CalculationContext context, int x, int y, int z) {
        if (!context.allowDownward) {
            return COST_INF;
        }
        if (!MovementHelper.canWalkOn(context, x, y - 2, z)) {
            return COST_INF;
        }
        BlockState down = context.get(x, y - 1, z);
        Block downBlock = down.getBlock();
        if (downBlock == Blocks.LADDER || downBlock == Blocks.VINE) {
            return LADDER_DOWN_ONE_COST;
        } else {
            // we're standing on it, while it might be block falling, it'll be air by the time we get here in the movement
            return FALL_N_BLOCKS_COST[1] + MovementHelper.getMiningDurationTicks(context, x, y - 1, z, down, false);
        }
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        boolean fall = dest.y < src.y - 1;
        if (ctx.playerFeet().equals(dest) && (!fall || ctx.player().onGround())) {
            return state.setStatus(MovementStatus.SUCCESS);
        } else if (!playerInValidPosition()) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        double diffX = ctx.player().position().x - (dest.getX() + 0.5);
        double diffZ = ctx.player().position().z - (dest.getZ() + 0.5);
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);

        if ((numTicks++ < 10 || fall) && ab < 0.2) {
            return state; // pushing into the ladder while centred would climb it
        }
        MovementHelper.moveTowards(ctx, state, positionsToBreak[0]);
        return state;
    }
}
