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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import com.google.common.collect.ImmutableSet;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.vector.Vector3d;

import java.util.Set;

/**
 * Free 3D movement through open water: straight down, and one block sideways while rising or
 * sinking. Baritone otherwise can only sink by falling to the floor and only moves sideways at a
 * fixed height, so it could not reach mid-water or seafloor targets like shipwreck chests.
 * Steering (swim pose, pitch) is done by {@link Movement#applySwim}; pure descent holds sneak.
 */
public class MovementSwim extends Movement {

    /** Ticks per block while sprint-swimming (~5 blocks/s). */
    public static final double SWIM_ONE_BLOCK_COST = 20 / 5.0;

    public MovementSwim(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, new BetterBlockPos[0]);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x - src.x, dest.y - src.y, dest.z - src.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest, new BetterBlockPos(dest.x, src.y, dest.z), new BetterBlockPos(src.x, dest.y, src.z));
    }

    private static boolean water(CalculationContext c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        // waterlogged slabs/stairs/chests report water but are solid
        return MovementHelper.isWater(s) && !(s.getBlock() instanceof net.minecraft.block.IWaterLoggable);
    }

    /** Water, or (at the surface) something the head can pass through. */
    private static boolean headroom(CalculationContext c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        return (MovementHelper.isWater(s) && !(s.getBlock() instanceof net.minecraft.block.IWaterLoggable)) || MovementHelper.canWalkThrough(c.bsi, x, y, z, s);
    }

    /** Ticks to clear a cell for swimming: 0 if already open, COST_INF if it can't/shouldn't be mined. */
    private static double dig(CalculationContext c, int x, int y, int z, boolean head) {
        BlockState s = c.get(x, y, z);
        if (head ? headroom(c, x, y, z) : water(c, x, y, z)) return 0;
        if (!c.allowBreak || s.getBlock() instanceof net.minecraft.block.ContainerBlock || Baritone.settings().blocksToDisallowBreaking.value.contains(s.getBlock())) return COST_INF;
        if (c.get(x, y + 1, z).getBlock() instanceof net.minecraft.block.FallingBlock) return COST_INF;
        double str = c.toolSet.getStrVsBlock(s);
        if (str <= 0) return COST_INF;
        // Underwater digging is 5x slower without Aqua Affinity, and another 5x while not on ground.
        return (1 / str + c.breakBlockAdditionalCost) * (c.aquaAffinity ? 5 : 25);
    }

    private BetterBlockPos[] digCells() {
        int dy = dest.y - src.y;
        if (Math.abs(dest.x - src.x) + Math.abs(dy) + Math.abs(dest.z - src.z) != 1) return new BetterBlockPos[0];
        if (dy < 0) return new BetterBlockPos[]{dest};
        if (dy > 0) return new BetterBlockPos[]{dest.up()};
        return new BetterBlockPos[]{dest, dest.up()};
    }

    public static double cost(CalculationContext c, int x, int y, int z, int dx, int dy, int dz) {
        if (!Baritone.settings().swimInWater.value) return COST_INF;
        int tx = x + dx, ty = y + dy, tz = z + dz;
        // Swimming, not walking: both ends must be in water with room for the head.
        // Dest may be the air block just above the surface (surfacing); it must sit on water.
        if (!water(c, x, y, z)) return COST_INF;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
            // Axis moves may dig: the new feet/head cells are water, passable, or mined out (slowly).
            double mine = 0;
            if (dy <= 0) mine += dig(c, tx, ty, tz, false);
            if (dy >= 0) mine += dig(c, tx, ty + 1, tz, true);
            if (mine >= COST_INF) return COST_INF;
            return SWIM_ONE_BLOCK_COST + mine;
        }
        boolean destOk = water(c, tx, ty, tz);
        if (!destOk || !headroom(c, tx, ty + 1, tz)) return COST_INF;
        if (dx != 0 || dz != 0) {
            // Don't clip a corner: the column we pass through on either leg must be open water too.
            if (!water(c, tx, y, tz) || !headroom(c, tx, y + 1, tz)) return COST_INF;
            if (dy < 0 && !water(c, x, ty, z)) return COST_INF;
            if (dy > 0 && !headroom(c, x, ty, z)) return COST_INF;
            if (dx != 0 && dz != 0 && (!water(c, x + dx, y, z) || !water(c, x, y, z + dz))) return COST_INF;
        }
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return SWIM_ONE_BLOCK_COST * dist;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        for (BetterBlockPos b : digCells()) {
            BlockState bs = ctx.world().getBlockState(b);
            if (MovementHelper.isWater(bs) && !(bs.getBlock() instanceof net.minecraft.block.IWaterLoggable)) continue;
            if (bs.getCollisionShape(ctx.world(), b).isEmpty() && bs.getFluidState().isEmpty()) continue;
            MovementHelper.switchToBestToolFor(ctx, bs);
            Rotation rot = RotationUtils.reachable(ctx, b, ctx.playerController().getBlockReachDistance())
                    .orElse(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(b), ctx.playerRotations()));
            state.setTarget(new MovementState.MovementTarget(rot, true));
            // Aim loosely: bobbing wobbles pitch, and seagrass/kelp in front gets broken first (instantly).
            Rotation cur = ctx.playerRotations();
            if (ctx.isLookingAt(b) || (Math.abs(cur.getYaw() - rot.getYaw()) % 360 < 4 && Math.abs(cur.getPitch() - rot.getPitch()) < 4)) {
                state.setInput(Input.CLICK_LEFT, true);
            }
            return state;
        }
        BetterBlockPos feet = ctx.playerFeet();
        Vector3d pos = ctx.player().getPositionVec();
        double hx = dest.x + 0.5 - pos.x, hz = dest.z + 0.5 - pos.z;
        double horiz = Math.sqrt(hx * hx + hz * hz);
        // Block-level arrival is enough: sprint-swimming carries momentum, so demanding the column
        // centre made the bot orbit the target (and drown). The next movement steers from here.
        boolean vertical = dest.x == src.x && dest.z == src.z;
        if (feet.equals(dest) && (!vertical || horiz < 0.5)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (!playerInValidPosition() && !MovementHelper.isWater(ctx, feet)) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (vertical || horiz < 0.35) {
            // Sink (or rise) in place: sneak/jump, nudge toward the column centre if drifting.
            if (dest.y < pos.y - 0.05) state.setInput(Input.SNEAK, true);
            else state.setInput(Input.JUMP, true);
            if (horiz > 0.2) MovementHelper.moveTowards(ctx, state, dest);
            return state;
        }
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vector3d(dest.x + 0.5, dest.y + 0.5, dest.z + 0.5), ctx.playerRotations());
        state.setTarget(new MovementState.MovementTarget(r, false));
        state.setInput(Input.MOVE_FORWARD, true);
        return state;
    }
}
