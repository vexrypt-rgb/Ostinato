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
import baritone.bastion.EdgeCost;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
        if (src.x == dest.x && src.z == dest.z) {
            return ImmutableSet.of(src, dest, new BetterBlockPos(dest.x, src.y, dest.z), new BetterBlockPos(src.x, dest.y, src.z));
        }
        // A swim lane is held loosely: breathing or bobbing lifts us off it (see the arrival rule in updateState),
        // and the next lane must accept where the last one left us, or the executor rewinds forever.
        return ImmutableSet.of(src, dest, new BetterBlockPos(dest.x, src.y, dest.z), new BetterBlockPos(src.x, dest.y, src.z),
                src.above(), src.above(2), src.above(3), src.below());
    }

    private static boolean water(CalculationContext c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        // waterlogged slabs/stairs/chests report water but are solid
        return MovementHelper.isWater(s) && !(s.getBlock() instanceof net.minecraft.world.level.block.SimpleWaterloggedBlock);
    }

    /** Water, or (at the surface) something the head can pass through. */
    private static boolean headroom(CalculationContext c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        return (MovementHelper.isWater(s) && !(s.getBlock() instanceof net.minecraft.world.level.block.SimpleWaterloggedBlock)) || MovementHelper.canWalkThrough(c.bsi, x, y, z, s);
    }

    /** Ticks to clear a cell for swimming: 0 if already open, COST_INF if it can't/shouldn't be mined. */
    private static double dig(CalculationContext c, int x, int y, int z, boolean head, boolean aboveCleared) {
        BlockState s = c.get(x, y, z);
        if (head ? headroom(c, x, y, z) : water(c, x, y, z)) return 0;
        if (!c.allowBreak || s.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock || Baritone.settings().blocksToDisallowBreaking.value.contains(s.getBlock())) return COST_INF;
        double str = c.toolSet.getStrVsBlock(s);
        if (str <= 0) return COST_INF;
        // Underwater digging is 5x slower without Aqua Affinity, and another 5x while not on ground.
        double t = (1 / str + c.breakBlockAdditionalCost) * (c.aquaAffinity ? 5 : 25);
        // Sand/gravel overhead drops into the gap and has to be dug again (a short stack is fine).
        for (int i = 1; !aboveCleared && c.get(x, y + i, z).getBlock() instanceof net.minecraft.world.level.block.FallingBlock; i++) {
            if (i > 3) return COST_INF;
            double fs = c.toolSet.getStrVsBlock(c.get(x, y + i, z));
            if (fs <= 0) return COST_INF;
            t += (1 / fs + c.breakBlockAdditionalCost) * (c.aquaAffinity ? 5 : 25);
        }
        return t;
    }

    /**
     * The planner still sees blocks we will have mined as solid. Other movements never end inside a
     * solid block, so a breakable solid src can only be a cell we swam/dug into: it floods, we swim in it.
     */
    private static boolean dugShaft(CalculationContext c, int x, int y, int z) {
        BlockState s = c.get(x, y, z);
        if (MovementHelper.canWalkThrough(c.bsi, x, y, z, s)) {
            // Just dug and not flooded yet (or a falling block left an air gap): fine if water touches it.
            return water(c, x, y + 1, z) || water(c, x + 1, y, z) || water(c, x - 1, y, z) || water(c, x, y, z + 1) || water(c, x, y, z - 1);
        }
        return dig(c, x, y, z, false, true) < COST_INF;
    }

    private BetterBlockPos[] digCells() {
        int dy = dest.y - src.y;
        if (Math.abs(dest.x - src.x) + Math.abs(dy) + Math.abs(dest.z - src.z) != 1) return new BetterBlockPos[0];
        if (dy < 0) return new BetterBlockPos[]{dest};
        if (dy > 0) return new BetterBlockPos[]{dest.above()};
        // Crawling under a roof: leave the roof alone, only the dest cell matters.
        BlockState srcHead = ctx.world().getBlockState(src.above());
        if (!srcHead.getCollisionShape(ctx.world(), src.above()).isEmpty() && !ctx.world().getBlockState(dest.above()).getCollisionShape(ctx.world(), dest.above()).isEmpty()) {
            return new BetterBlockPos[]{dest};
        }
        return new BetterBlockPos[]{dest, dest.above()};
    }

    /** A thin sheet of flowing water on a floor (a stream down steps): you wade it, you can't swim up into it. */
    private static boolean shallow(CalculationContext c, int x, int y, int z) {
        net.minecraft.world.level.material.FluidState f = c.get(x, y, z).getFluidState();
        return !f.isSource() && !f.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING) && !water(c, x, y - 1, z);
    }

    private boolean falling(BlockPos p) {
        net.minecraft.world.level.material.FluidState f = ctx.world().getFluidState(p);
        return MovementHelper.isWater(ctx, p) && (f.isSource() || f.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING));
    }

    private static boolean current(CalculationContext c, int x, int y, int z) {
        net.minecraft.world.level.material.FluidState f = c.get(x, y, z).getFluidState();
        return !f.isSource() && !f.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING);
    }

    private static boolean falling(CalculationContext c, int x, int y, int z) {
        net.minecraft.world.level.material.FluidState f = c.get(x, y, z).getFluidState();
        return !f.isEmpty() && !f.isSource() && f.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING);
    }

    public static double cost(CalculationContext c, int x, int y, int z, int dx, int dy, int dz) {
        if (!Baritone.settings().swimInWater.value) return COST_INF;
        int tx = x + dx, ty = y + dy, tz = z + dz;
        // never swim into lava: dig() treats a fluid cell as open, so a dug shaft next to lava was planned straight through it
        if (MovementHelper.isLava(c.get(tx, ty, tz)) || MovementHelper.isLava(c.get(tx, ty + 1, tz))) return COST_INF;
        // intentional swimming skips the generic flow penalty, but not water that carries us toward a drop or lava
        if (EdgeCost.waterPush(c, tx, ty, tz) > 0) return COST_INF;
        if (dy == 1 && Math.abs(dx) + Math.abs(dz) == 1 && water(c, x, y, z) && MovementHelper.canWalkOn(c.bsi, tx, y, tz)
                && headroom(c, tx, ty, tz) && headroom(c, tx, ty + 1, tz) && headroom(c, x, ty, z) && headroom(c, x, ty + 1, z)) {
            // Up a step out of water (a stream down stairs): pushing into the edge while jumping takes
            // vanilla's climb-out boost, far quicker than rising against the falling water first.
            return SWIM_ONE_BLOCK_COST * 1.5;
        }
        // A stream down steps is wading depth: walk it (ascend/traverse), there's nothing to swim in.
        // Rising into sideways-flowing water fights the current: take the step as an ascend instead.
        if (dy > 0 && ((water(c, x, y, z) && shallow(c, x, y, z)) || ((dx != 0 || dz != 0) && water(c, tx, ty, tz) && current(c, tx, ty, tz)))) return COST_INF;
        // Falling water shoves down harder than a diagonal swim climbs: stuck at the foot of a waterfall. Straight up still works.
        if (dy > 0 && (dx != 0 || dz != 0) && (falling(c, tx, ty, tz) || falling(c, tx, y, tz))) return COST_INF;
        // Swimming, not walking: both ends must be in water with room for the head.
        // Dest may be the air block just above the surface (surfacing); it must sit on water.
        if (!water(c, x, y, z) && !dugShaft(c, x, y, z)) return COST_INF;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
            // Axis moves may dig: the new feet/head cells are water, passable, or mined out (slowly).
            double mine = 0;
            // Can't float in the air above the surface: rising must end in water.
            if (dy > 0 && !water(c, tx, ty, tz)) return COST_INF;
            // Sinking, the cell above the dug one is where we are; sideways, the head cell is dug too.
            if (dy <= 0) mine += dig(c, tx, ty, tz, false, true);
            // Swim pose is one block tall: already squeezed under a roof, a 1-high gap sideways is enough.
            boolean crawl = dy == 0 && !headroom(c, x, y + 1, z) && !headroom(c, tx, ty + 1, tz) && water(c, tx, ty, tz);
            if (dy >= 0 && !crawl) mine += dig(c, tx, ty + 1, tz, true, false);
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

    private int stallTicks;

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        for (BetterBlockPos b : digCells()) {
            BlockState bs = ctx.world().getBlockState(b);
            if (MovementHelper.isWater(bs) && !(bs.getBlock() instanceof net.minecraft.world.level.block.SimpleWaterloggedBlock)) continue;
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
        Vec3 pos = ctx.player().position();
        double hx = dest.x + 0.5 - pos.x, hz = dest.z + 0.5 - pos.z;
        double horiz = Math.sqrt(hx * hx + hz * hz);
        // Block-level arrival is enough: sprint-swimming carries momentum, so demanding the column
        // centre made the bot orbit the target (and drown). The next movement steers from here.
        boolean vertical = dest.x == src.x && dest.z == src.z;
        if ((feet.equals(dest) && (!vertical || horiz < 0.5))
                || (!vertical && dest.y <= src.y && MovementHelper.atSwum(ctx, dest) && !MovementHelper.isWater(ctx, dest.above()))
                // Rising for air (or bobbing) through the lane: the column counts, or it overshoots and turns back.
                || (breathing && !vertical && feet.x == dest.x && feet.z == dest.z && feet.y > dest.y && feet.y <= dest.y + 3)) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (!playerInValidPosition() && !MovementHelper.isWater(ctx, feet)) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (!vertical && dest.y > src.y && MovementHelper.isWater(ctx, dest)) {
            // Up a stream: the swim pose, once started, lasts while any of us is in water and follows the
            // look vector at sprint speed, far faster than bobbing up each step against the fall.
            // It only starts with the eyes under, so sink for it first.
            // Swim thrust follows yaw alone; pitch sets the climb. Look steeply up and let yaw carry us over.
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(dest.x + 0.5, dest.y + 1, dest.z + 0.5), ctx.playerRotations());
            r = new Rotation(r.getYaw(), -75);
            state.setTarget(new MovementState.MovementTarget(r, false));
            state.setInput(Input.MOVE_FORWARD, true);
            state.setInput(Input.SPRINT, true);
            // Looking up only steers the swim pose upward while jumping (or with water overhead).
            state.setInput(Input.JUMP, true);
            if (!ctx.player().isSwimming() && !ctx.player().isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
                state.setInput(Input.SNEAK, true);
            }
            return state;
        }
        if (vertical && dest.y > src.y && falling(dest) && falling(dest.above()) && ctx.player().isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
            // Up a waterfall: sprint-swim looking straight up, pressed into a side wall so forward thrust
            // can't carry us out of the column.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = feet.relative(d);
                if (!ctx.world().getBlockState(w).getCollisionShape(ctx.world(), w).isEmpty()) {
                    state.setTarget(new MovementState.MovementTarget(new Rotation(d.toYRot(), -85), false));
                    state.setInput(Input.MOVE_FORWARD, true);
                    state.setInput(Input.SPRINT, true);
                    state.setInput(Input.JUMP, true);
                    return state;
                }
            }
        }
        if (vertical || horiz < 0.35) {
            // Sink (or rise) in place: sneak/jump, nudge toward the column centre if drifting.
            if (dest.y < pos.y - 0.05) state.setInput(Input.SNEAK, true);
            else state.setInput(Input.JUMP, true);
            if (horiz > 0.2) MovementHelper.moveTowards(ctx, state, dest);
            return state;
        }
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(dest.x + 0.5, dest.y + 0.5, dest.z + 0.5), ctx.playerRotations());
        state.setTarget(new MovementState.MovementTarget(r, false));
        state.setInput(Input.MOVE_FORWARD, true);
        // Against a step edge (a stream down stairs) or sagging below the lane: jumping in water
        // against a wall is vanilla's climb-out boost; it also keeps us up in a current.
        if (++stallTicks % 40 == 0) {
            net.minecraft.world.entity.player.Player pl = ctx.player();
            logDebug(String.format("swim stall %s->%s pos=%.2f,%.2f,%.2f swim=%b sprint=%b food=%d hcol=%b vcol=%b eye=%b",
                    src, dest, pos.x, pos.y, pos.z, pl.isSwimming(), pl.isSprinting(), pl.getFoodData().getFoodLevel(),
                    pl.horizontalCollision, pl.verticalCollision, pl.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)));
        }
        // A shore shelf counts too: floating, the feet hang below the feet block, into the lip of the block under dest.
        boolean step = !MovementHelper.isWater(ctx, new BetterBlockPos(dest.x, feet.y, dest.z))
                || (pos.y < feet.y && !MovementHelper.isWater(ctx, new BetterBlockPos(dest.x, feet.y - 1, dest.z))
                    && !ctx.world().getBlockState(new BetterBlockPos(dest.x, feet.y - 1, dest.z)).getCollisionShape(ctx.world(), new BetterBlockPos(dest.x, feet.y - 1, dest.z)).isEmpty());
        if (ctx.player().horizontalCollision && !step) {
            // Head against a roof lip over open water: sink under it, jumping only wedges us into the edge.
            // keep MOVE_FORWARD: without it applySwim never runs and the bot just floats against the lip
            state.setInput(Input.SNEAK, true);
        } else if (dest.y >= feet.y && ((ctx.player().horizontalCollision && step) || pos.y < dest.y - 0.1)) {
            state.setInput(Input.JUMP, true);
        }
        return state;
    }
}
