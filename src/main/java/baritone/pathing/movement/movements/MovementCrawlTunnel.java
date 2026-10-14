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
import baritone.api.utils.input.Input;
import baritone.behavior.InventoryBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CrawlCosts;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;
import java.util.Set;

/**
 * Ostinato: a 1-high (crawl) tunnel in a straight line, {@code length} blocks long.
 * <p>
 * Pose is not part of a path node, so the whole crawl is one movement that starts and ends standing:
 * <ol>
 *     <li>entry cell B = src + dir: mine feet and head, place a trapdoor in the bottom half of B's head cell (or a
 *     fence gate) against the next head block, open it, step under it, close it. A closed bottom-half trapdoor
 *     leaves 1.19 blocks of headroom, so Player.updatePlayerPose puts us in the swimming (crawl) pose.</li>
 *     <li>crawl cells: mine only the feet block, crawl in. The head block is never touched.</li>
 *     <li>exit cell E = dest: mine feet and head, crawl in, the pose goes back to standing by itself.</li>
 * </ol>
 * The trapdoor stays behind (its cost is {@code crawlTunnelItemCost}).
 */
public class MovementCrawlTunnel extends Movement {

    public static final int[] LENGTHS = {4, 8, 16};

    private final Direction dir;
    private final int length;
    private final BetterBlockPos entry;
    private final BetterBlockPos entryHead;

    private enum Phase { ENTRY_MINE, PLACE, OPEN, STEP_IN, CLOSE, CRAWL, DONE }

    private Phase phase = Phase.ENTRY_MINE;
    private int cell = 2; // index (in blocks from src) of the cell we are working towards while crawling
    private int clickCooldown = 0;
    private int ticks = 0;
    /** Ostinato: doing this one as a sprint-crawl (decided when it starts running). */
    private Boolean sprint = null;

    public MovementCrawlTunnel(IBaritone baritone, BetterBlockPos src, Direction dir, int length) {
        super(baritone, src, src.relative(dir, length), new BetterBlockPos[]{src.relative(dir).above(), src.relative(dir)});
        this.dir = dir;
        this.length = length;
        this.entry = src.relative(dir);
        this.entryHead = entry.above();
    }

    @Override
    public void reset() {
        super.reset();
        phase = Phase.ENTRY_MINE;
        cell = 2;
        clickCooldown = 0;
        ticks = 0;
        sprint = null;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dir, length);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        ImmutableSet.Builder<BetterBlockPos> b = ImmutableSet.builder();
        for (int i = 0; i <= length; i++) {
            b.add(src.relative(dir, i));
        }
        return b.build();
    }

    /** Is something with the right shape to force the crawl in the entry head cell, and which item. */
    static boolean isCrawlItemBlock(BlockState s) {
        return s.getBlock() instanceof TrapDoorBlock || s.getBlock() instanceof FenceGateBlock;
    }

    /** Where the collision of a head cell starts (0..1), NaN if it has none. Uses the shape of the state alone. */
    static double collisionBottom(BlockState state) {
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return Double.NaN;
        }
        return shape.min(Direction.Axis.Y);
    }

    public static double cost(CalculationContext context, int x, int y, int z, Direction dir, int length) {
        if (!context.allowCrawlTunnel || !context.allowBreak || length < 4) {
            return COST_INF;
        }
        int dx = dir.getStepX(), dz = dir.getStepZ();
        BlockState srcFeet = context.get(x, y, z);
        if (MovementHelper.isLiquid(srcFeet) || MovementHelper.isLiquid(context.get(x, y + 1, z))) {
            return COST_INF; // in water we already swim; that's MovementSwim's job
        }
        if (!MovementHelper.canWalkOn(context, x, y - 1, z)) {
            return COST_INF;
        }
        // entry cell: both blocks go, floor stays, nothing may fall into the head cell where the trapdoor goes
        int bx = x + dx, bz = z + dz;
        double entryMine = twoHigh(context, bx, y, bz);
        if (entryMine >= COST_INF) {
            return COST_INF;
        }
        BlockState aboveEntryHead = context.get(bx, y + 2, bz);
        if (aboveEntryHead.getBlock() instanceof FallingBlock || MovementHelper.isLiquid(aboveEntryHead)) {
            return COST_INF;
        }
        // something to hang the trapdoor on: the first crawl cell's head block, which must stay solid anyway
        if (!MovementHelper.canPlaceAgainst(context.bsi, x + 2 * dx, y + 1, z + 2 * dz)) {
            return COST_INF;
        }
        if (context.costOfPlacingAt(bx, y + 1, bz, context.get(bx, y + 1, bz)) >= COST_INF) {
            return COST_INF; // a builder or a hook says no placing there
        }
        double[] cells = new double[length - 2];
        for (int i = 2; i < length; i++) {
            int cx = x + i * dx, cz = z + i * dz;
            double m = crawlCell(context, cx, y, cz);
            if (m >= COST_INF) {
                return COST_INF;
            }
            cells[i - 2] = m;
        }
        int ex = x + length * dx, ez = z + length * dz;
        double exitMine = twoHigh(context, ex, y, ez);
        double itemCost = context.crawlTunnelItemCost;
        double walk = CrawlCosts.tunnelCost(entryMine, cells, exitMine, context.crawlStepCost, itemCost);
        if (!context.sprintCrawl || exitMine >= COST_INF) {
            return walk;
        }
        // Ostinato: sprint-crawl, split the exit into feet (can be mined on the run) and head
        BlockState exFeet = context.get(ex, y, ez);
        double exitFeet = MovementHelper.getMiningDurationTicks(context, ex, y, ez, exFeet, false);
        double sprint = CrawlCosts.sprintTunnelCost(entryMine, cells, exitFeet, exitMine - exitFeet,
                context.crawlStepCost, context.sprintCrawlStepCost, itemCost);
        return Math.min(walk, sprint);
    }

    /** Ostinato: would this tunnel be done as a sprint-crawl (it is the cheaper of the two). */
    public static boolean prefersSprint(CalculationContext context, int x, int y, int z, Direction dir, int length) {
        if (!context.sprintCrawl) {
            return false;
        }
        boolean was = context.sprintCrawl;
        context.sprintCrawl = false;
        double walk;
        try {
            walk = cost(context, x, y, z, dir, length);
        } finally {
            context.sprintCrawl = was;
        }
        return cost(context, x, y, z, dir, length) < walk;
    }

    /** Mining a full 2-high cell we will stand in, with a walkable floor. */
    private static double twoHigh(CalculationContext context, int x, int y, int z) {
        BlockState feet = context.get(x, y, z);
        BlockState head = context.get(x, y + 1, z);
        if (MovementHelper.isLava(feet) || MovementHelper.isLava(head) || MovementHelper.isLiquid(feet) || MovementHelper.isLiquid(head)) {
            return COST_INF;
        }
        if (MovementHelper.avoidWalkingInto(feet) || MovementHelper.avoidWalkingInto(head)) {
            return COST_INF;
        }
        if (!MovementHelper.canWalkOn(context, x, y - 1, z)) {
            return COST_INF; // no bridging under a tunnel (nether lava lakes, the end void)
        }
        double f = MovementHelper.getMiningDurationTicks(context, x, y, z, feet, false);
        if (f >= COST_INF) {
            return COST_INF;
        }
        double h = MovementHelper.getMiningDurationTicks(context, x, y + 1, z, head, true);
        if (h >= COST_INF) {
            return COST_INF;
        }
        return f + h;
    }

    /** Mining the feet block of a crawl cell. The head block must force the crawl and must never come down on us. */
    private static double crawlCell(CalculationContext context, int x, int y, int z) {
        BlockState head = context.get(x, y + 1, z);
        if (head.getBlock() instanceof FallingBlock || !head.getFluidState().isEmpty()) {
            return COST_INF; // gravel / sand / concrete powder falls into the 1-high gap and suffocates us; water or lava pours in
        }
        if (!CrawlCosts.forcesCrawl(collisionBottom(head))) {
            return COST_INF; // we'd stand up here and then not fit into the next cell
        }
        if (MovementHelper.avoidWalkingInto(head)) {
            return COST_INF; // magma, cactus and such
        }
        BlockState feet = context.get(x, y, z);
        if (!feet.getFluidState().isEmpty() || MovementHelper.avoidWalkingInto(feet)) {
            return COST_INF;
        }
        if (!MovementHelper.canWalkOn(context, x, y - 1, z)) {
            return COST_INF;
        }
        if (MovementHelper.canWalkThrough(context, x, y, z, feet)) {
            if (!MovementHelper.fullyPassable(context, x, y, z, feet)) {
                return COST_INF; // a partial block (snow layer, carpet) eats into 0.6 of headroom; keep it simple
            }
            return 0;
        }
        // avoidBreaking (inside) refuses with liquid beside or above the block
        return MovementHelper.getMiningDurationTicks(context, x, y, z, feet, false);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state); // mines the entry cell (positionsToBreak) before RUNNING
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        ticks++;
        if (clickCooldown > 0) {
            clickCooldown--;
        }
        if (ticks > 40 * 20 + length * 20 * 20) {
            logDebug("crawl tunnel timed out");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        BlockPos feet = ctx.playerFeet();
        if (phase == Phase.ENTRY_MINE) {
            phase = Phase.PLACE;
        }
        if (sprint == null) {
            sprint = prefersSprint(new CalculationContext(baritone), src.x, src.y, src.z, dir, length);
        }
        BlockState trap = BlockStateInterface.get(ctx, entryHead);
        switch (phase) {
            case PLACE: {
                if (isCrawlItemBlock(trap)) {
                    phase = Phase.OPEN;
                    return state;
                }
                if (!trap.isAir() && !MovementHelper.canWalkThrough(ctx, entryHead)) {
                    return state.setStatus(MovementStatus.UNREACHABLE); // something filled it
                }
                if (!feet.equals(src)) {
                    MovementHelper.moveTowards(ctx, state, src);
                    return state;
                }
                InventoryBehavior inv = ((Baritone) baritone).getInventoryBehavior();
                if (!inv.pickCrawlItem(true)) {
                    logDebug("crawl tunnel: no trapdoor or fence gate left");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                // the lower half of the face of the first crawl head block that looks back at us: a bottom-half trapdoor
                BlockPos against = entryHead.relative(dir);
                Direction face = dir.getOpposite();
                Vec3 spot = new Vec3(against.getX() + 0.5 + face.getStepX() * 0.5, against.getY() + 0.25, against.getZ() + 0.5 + face.getStepZ() * 0.5);
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, against, spot, ctx.playerController().getBlockReachDistance(), false);
                Rotation r = rot.orElse(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), spot, ctx.playerRotations()));
                state.setTarget(new MovementState.MovementTarget(r, true));
                if (clickCooldown == 0 && ctx.isLookingAt(against) && ctx.playerRotations().isReallyCloseTo(r)) {
                    state.setInput(Input.CLICK_RIGHT, true);
                    clickCooldown = 4;
                }
                return state;
            }
            case OPEN: {
                if (!isCrawlItemBlock(trap)) {
                    phase = Phase.PLACE;
                    return state;
                }
                if (trap.getBlock() instanceof TrapDoorBlock && trap.getValue(BlockStateProperties.HALF) == Half.TOP) {
                    // landed in the top half: closed it only leaves 1.81 of room. Mine it and try again.
                    return mine(state, entryHead);
                }
                if (trap.getValue(BlockStateProperties.OPEN)) {
                    phase = Phase.STEP_IN;
                    return state;
                }
                return click(state, entryHead, 0.08);
            }
            case STEP_IN: {
                if (sprint) {
                    return sprintIn(state, trap, feet);
                }
                if (!trap.getValue(BlockStateProperties.OPEN)) {
                    phase = feet.equals(entry) ? Phase.CLOSE : Phase.OPEN;
                    return state;
                }
                Vec3 pos = ctx.player().position();
                double off = Math.max(Math.abs(pos.x - (entry.getX() + 0.5)), Math.abs(pos.z - (entry.getZ() + 0.5)));
                if (feet.equals(entry) && off < 0.2) {
                    phase = Phase.CLOSE;
                    return state;
                }
                MovementHelper.moveTowards(ctx, state, entry);
                return state.setInput(Input.SNEAK, off < 0.35); // creep the last bit so we don't push into the panel
            }
            case CLOSE: {
                if (!isCrawlItemBlock(trap)) {
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                if (!trap.getValue(BlockStateProperties.OPEN)) {
                    if (ctx.player().getPose() == Pose.SWIMMING) {
                        phase = Phase.CRAWL;
                    }
                    return state; // the pose updates next tick
                }
                // the open panel leans against the far side of the head cell
                return click(state, entryHead, 0.5 - CrawlCosts.TRAPDOOR_THICKNESS / 2 - 0.02);
            }
            case CRAWL: {
                if (sprint && ctx.player().isSprinting()) {
                    return sprintCrawl(state, feet);
                }
                if (feet.equals(dest)) {
                    phase = Phase.DONE;
                    return state.setStatus(MovementStatus.SUCCESS);
                }
                BetterBlockPos target = src.relative(dir, cell);
                if (feet.equals(target) && cell < length) {
                    cell++;
                    target = src.relative(dir, cell);
                }
                // mine what is in the way: feet only for crawl cells, both for the exit
                if (!MovementHelper.canWalkThrough(ctx, target)) {
                    return mine(state, target);
                }
                if (cell == length && !MovementHelper.canWalkThrough(ctx, target.above())) {
                    return mine(state, target.above());
                }
                if (ctx.player().getPose() != Pose.SWIMMING && cell < length && !feet.equals(src.relative(dir, length - 1))) {
                    logDebug("crawl tunnel: stood up mid tunnel");
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                MovementHelper.moveTowards(ctx, state, target);
                return state;
            }
            default:
                return state;
        }
    }

    /** Ostinato: how far the player centre is past src's centre along the tunnel. */
    private double progress() {
        Vec3 p = ctx.player().position();
        return (p.x - (src.getX() + 0.5)) * dir.getStepX() + (p.z - (src.getZ() + 0.5)) * dir.getStepZ();
    }

    /**
     * Ostinato: sprint entry. From the start, mine the first crawl cell's feet (so nothing stops us), then sprint
     * forward and close the trapdoor while passing under it. The pose flips to crawl the next tick and the running
     * sprint is kept (only starting one is refused). The window: our front edge is under the trapdoor
     * (progress > 0.2) and has not yet reached the first crawl cell's head block (progress < 1.2), minus a tick for
     * the pose; at ~0.28 blocks a tick that is three ticks or so.
     */
    private MovementState sprintIn(MovementState state, BlockState trap, BlockPos feet) {
        BetterBlockPos first = src.relative(dir, 2);
        if (!MovementHelper.canWalkThrough(ctx, first)) {
            if (!feet.equals(src)) {
                MovementHelper.moveTowards(ctx, state, src);
                return state;
            }
            return mine(state, first);
        }
        double prog = progress();
        if (!trap.getValue(BlockStateProperties.OPEN)) {
            // closed on us: crawling now if it worked
            if (ctx.player().getPose() == Pose.SWIMMING) {
                phase = Phase.CRAWL;
                cell = 2;
                return sprintCrawl(state, feet);
            }
            return state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
        }
        if (prog > 1.15 || (prog > 0.3 && ctx.player().horizontalCollision)) {
            // missed the window: fall back to the plain entry (sprint is lost, cost was planned with it, so it's slower)
            logDebug("crawl tunnel: missed the sprint close, closing standing");
            sprint = false;
            return state;
        }
        if (prog < -0.25) {
            MovementHelper.moveTowards(ctx, state, src);
            return state;
        }
        // look at the open panel the whole run, keys go by yaw (straight down the tunnel)
        Vec3 spot = new Vec3(entryHead.getX() + 0.5 + dir.getStepX() * (0.5 - CrawlCosts.TRAPDOOR_THICKNESS / 2 - 0.02), entryHead.getY() + 0.5,
                entryHead.getZ() + 0.5 + dir.getStepZ() * (0.5 - CrawlCosts.TRAPDOOR_THICKNESS / 2 - 0.02));
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), spot, ctx.playerRotations());
        state.setTarget(new MovementState.MovementTarget(r, true));
        state.setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, true);
        if (prog > 0.2 && clickCooldown == 0 && ctx.isLookingAt(entryHead) && ctx.player().isSprinting()) {
            state.setInput(Input.CLICK_RIGHT, true);
            clickCooldown = 4;
        }
        return state;
    }

    /**
     * Ostinato: crawl while keeping the sprint: never let go of W (that ends it), and mine the next closed block
     * of the tunnel while moving towards it so we never bump into it (a head-on bump ends it too). If a block is
     * too slow to break in time we bump, the sprint ends, and the plain crawl code takes over.
     */
    private MovementState sprintCrawl(MovementState state, BlockPos feet) {
        if (feet.equals(dest)) {
            if (!MovementHelper.canWalkThrough(ctx, dest.above())) {
                return mine(state, dest.above()); // stand up only once the exit is 2 high
            }
            phase = Phase.DONE;
            return state.setStatus(MovementStatus.SUCCESS);
        }
        int at = 0;
        for (int i = 0; i <= length; i++) {
            if (src.relative(dir, i).equals(feet)) {
                at = i;
            }
        }
        cell = Math.max(cell, Math.min(length, at + 1));
        BetterBlockPos toward = src.relative(dir, Math.min(length, at + 1));
        BlockPos block = null;
        for (int i = at + 1; i <= Math.min(length, at + 3) && block == null; i++) {
            BetterBlockPos c = src.relative(dir, i);
            if (!MovementHelper.canWalkThrough(ctx, c)) {
                block = c;
            }
        }
        if (block != null) {
            mine(state, block);
            // keep W down whatever we look at; with the yaw down the tunnel it is plain forward
            MovementHelper.moveTowardsWithoutRotation(ctx, state, toward);
        } else if (at >= length - 1 && !MovementHelper.canWalkThrough(ctx, dest.above())) {
            // last cell: run into the exit and mine its head from inside (the head doesn't block a crawler)
            MovementHelper.moveTowards(ctx, state, dest);
        } else {
            MovementHelper.moveTowards(ctx, state, toward);
        }
        return state.setInput(Input.SPRINT, true);
    }

    private MovementState click(MovementState state, BlockPos pos, double towardsFar) {
        Vec3 spot = new Vec3(pos.getX() + 0.5 + dir.getStepX() * towardsFar, pos.getY() + 0.09, pos.getZ() + 0.5 + dir.getStepZ() * towardsFar);
        if (phase == Phase.CLOSE) {
            spot = new Vec3(spot.x, pos.getY() + 0.5, spot.z);
        }
        Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, spot, ctx.playerController().getBlockReachDistance(), false);
        Rotation r = rot.orElse(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), spot, ctx.playerRotations()));
        state.setTarget(new MovementState.MovementTarget(r, true));
        if (clickCooldown == 0 && ctx.isLookingAt(pos)) {
            state.setInput(Input.CLICK_RIGHT, true);
            clickCooldown = 4;
        }
        return state;
    }

    private MovementState mine(MovementState state, BlockPos pos) {
        MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos));
        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
        Rotation r = rot.orElse(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), Vec3.atCenterOf(pos), ctx.playerRotations()));
        state.setTarget(new MovementState.MovementTarget(r, true));
        if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(r)) {
            state.setInput(Input.CLICK_LEFT, true);
        }
        return state;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // once under the trapdoor, cancelling would leave the next path planning as if we could stand
        return phase.ordinal() < Phase.CLOSE.ordinal();
    }
}
