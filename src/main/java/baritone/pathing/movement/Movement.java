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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.utils.BlockStateInterface;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IBaritone baritone;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(baritone, src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) baritone.getPathingBehavior()).pathStart());
    }

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        // Unconditional JUMP-in-liquid causes bobbing stuck (cabaletta/baritone#2377).
        // In deep water the swim controller steers instead (real sprint-swim, #3988).
        boolean swimming = applySwim(currentState);
        if (!swimming && MovementHelper.isLiquid(ctx, ctx.playerFeet()) && ctx.player().position().y < dest.y + 0.6
                && !(Baritone.settings().swimInWater.value && this instanceof baritone.pathing.movement.movements.MovementTraverse)) {
            currentState.setInput(Input.JUMP, true);
        }
        if (ctx.player().isInWall()) {
            ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
        currentState.getTarget().getRotation().ifPresent(rotation ->
                baritone.getLookBehavior().updateTarget(
                        rotation,
                        currentState.getTarget().hasToForceRotations()));
        baritone.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) -> {
            baritone.getInputOverrideHandler().setInputForceState(input, forced);
        });
        currentState.getInputStates().clear();

        // If the current status indicates a completed movement
        if (currentState.getStatus().isComplete()) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    /** Surfacing for air in the swim pose; shared by movements so it survives path segments. */
    public static boolean breathing;

    /** Blocks from the eyes up to open air straight above, or -1 if roofed over within 24. */
    private double surfaceAbove(net.minecraft.world.entity.player.Player p) {
        BlockPos eyes = BlockPos.containing(p.getEyePosition(1));
        for (int i = 0; i < 24; i++) {
            BlockPos q = eyes.above(i);
            net.minecraft.world.level.block.state.BlockState st = ctx.world().getBlockState(q);
            if (MovementHelper.isWater(st)) continue;
            return st.getCollisionShape(ctx.world(), q).isEmpty() && st.getFluidState().isEmpty() ? Math.max(0, q.getY() - 0.11 - p.getEyeY()) : -1;
        }
        return -1;
    }

    /** Whether the air straight above the eyes is capped by a solid block within a few blocks (a cave pocket, not open water). */
    private boolean roofedAbove(net.minecraft.world.entity.player.Player p) {
        BlockPos q = BlockPos.containing(p.getEyePosition(1));
        while (MovementHelper.isWater(ctx.world().getBlockState(q)) && q.getY() < ctx.world().getMaxY()) q = q.above();
        for (int i = 0; i < 4; i++, q = q.above()) {
            net.minecraft.world.level.block.state.BlockState st = ctx.world().getBlockState(q);
            if (!st.getCollisionShape(ctx.world(), q).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Vanilla sprint-swimming, for every movement type. Vanilla only enters the swim pose when the
     * player sprints with its EYES under water, and while swimming vertical motion follows pitch.
     * Holding JUMP (the old behaviour) keeps the head above the surface, so the bot paddled on top
     * forever; a fixed -30 pitch could only ever rise. This dips the head under to start swimming,
     * then steers pitch toward the destination height and surfaces when air runs low.
     *
     * @return true if it took over steering (the caller must not force JUMP)
     */
    private boolean applySwim(MovementState state) {
        if (!Baritone.settings().swimInWater.value || currentState.getStatus().isComplete()) return false;
        net.minecraft.world.entity.player.Player p = ctx.player();
        if (!p.isInWater() || p.isPassenger()) return false;
        if (!Boolean.TRUE.equals(state.getInputStates().get(Input.MOVE_FORWARD))) return false;
        BlockPos feet = ctx.playerFeet();
        // Entering the swim pose needs room for the body: water at the head, or (floating at the surface)
        // water under the feet to sneak-sink into. Once swimming, one block is enough.
        if (!MovementHelper.isWater(ctx, feet)) return false;
        if (!p.isSwimming() && !MovementHelper.isWater(ctx, feet.above()) && !MovementHelper.isWater(ctx, feet.below())) return false;
        // Swimming into a step face (a stream down stairs): stop swimming and let JUMP climb it.
        // A roof lip at head height isn't a step: the swim pose is what fits under it, so keep swimming.
        BlockPos lip = new BlockPos(dest.x, feet.getY() - 1, dest.z);
        boolean shelf = p.position().y < feet.getY() && !MovementHelper.isWater(ctx, lip) && !ctx.world().getBlockState(lip).getCollisionShape(ctx.world(), lip).isEmpty();
        if (p.horizontalCollision && dest.y >= feet.getY() && (shelf || !MovementHelper.isWater(ctx, new BlockPos(dest.x, feet.getY(), dest.z)))) return false;
        // Climbing out onto land needs JUMP against the bank: leave that to the normal path.
        if (!MovementHelper.isWater(ctx, dest) && !MovementHelper.isWater(ctx, dest.below())
                && dest.y >= feet.getY()) return false;

        // A lane drawn over the surface: in the swim pose the eyes are only ~0.4 above the feet, so ride with
        // the feet just under the surface line; the head then dips in and out and keeps breathing.
        boolean surfaceLane = p.isSwimming() && ctx.world().isEmptyBlock(dest.above()) && MovementHelper.isWater(ctx, dest.below());
        double dy = dest.y + (surfaceLane ? 0.45 : 0) - p.position().y;
        int air = p.getAirSupply(), max = p.getMaxAirSupply();
        double toSurface = surfaceAbove(p);
        // Start rising while there's still time to creep up in the swim pose (~0.1 block/tick at -30).
        if (toSurface >= 0 && air < toSurface * 10 + 60) breathing = true;
        // A roofed air pocket overhead may be the last air for a while: top up to full before swimming on.
        boolean pocket = toSurface >= 0 && toSurface < 2 && roofedAbove(p);
        if (pocket && air < max - 20) breathing = true;
        if (air >= max || toSurface < 0) breathing = false;
        float pitch;
        if (breathing) {
            // Keep sprint-swimming, heading for the surface: steep only if air is short, then a slow creep
            // so the eyes break the surface without leaving the swim pose. Once out, hold just there.
            if (!p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) pitch = -2f;
            else if (toSurface > 1.5) pitch = (float) -Math.min(45, Math.max(12, Math.toDegrees(Math.asin(Math.min(1, toSurface / Math.max(20, air * 0.6) / 0.18)))));
            else pitch = -8f;
            if (!p.isSwimming() && air > 30) pitch = 35f; // get into the swim pose first
        } else if (!p.isSwimming()) {
            pitch = air < 30 ? -35f : 35f;   // dip the eyes under so sprint engages the swim pose
        } else if (dy > 0.5) {
            pitch = -35f;                  // rise
        } else if (dy < -0.5) {
            pitch = 30f;                   // dive
        } else {
            // cruise just under the surface; if the head breaches, nose back down or the pose drops
            pitch = !p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) ? 6f : -8f;
        }
        float yaw = state.getTarget().getRotation().map(Rotation::getYaw)
                .orElse(ctx.playerRotations().getYaw());
        state.setInput(Input.SPRINT, true);
        // On the surface lane the swim pose settles with the eyes a hair under the waterline and pitch alone
        // won't lift it: kick up with JUMP once air runs low so the head breaks the surface on the move.
        boolean kick = surfaceLane && p.isSwimming() && air < 100 && p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        state.setInput(Input.JUMP, (air < 30 && !p.isSwimming()) || kick);
        // Pitch does nothing until the swim pose, and the pose needs the eyes under: at the surface the
        // bot otherwise paddles upright forever at a third of swim speed. Sink the eyes in with sneak.
        // Not while standing: sneak on the ground is safe-walk, which pins us to the ledge of a shallow shelf.
        state.setInput(Input.SNEAK, !p.onGround() && !p.isSwimming() && air >= 30 && !breathing && !p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
        if (breathing && pocket) {
            if (p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
                // the pocket is only a few blocks long: climb into it steeply instead of cruising past
                pitch = -60f;
                state.setInput(Input.SPRINT, false);
            } else {
                state.setInput(Input.MOVE_FORWARD, false); // hold in the pocket until the lungs are full
            }
        }
        state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, pitch), true));
        return true;
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() && Baritone.settings().pauseMiningForFallingBlocks.value) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
                if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(blockPos.x, blockPos.y, blockPos.z)) {
                    // kept out of rather than blocked: mining opens nothing, so the path is replanned around it
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return true;
                }
                somethingInTheWay = true;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, blockPos));
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));
                    if (ctx.isLookingAt(blockPos) || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)) {
                        state.setInput(Input.CLICK_LEFT, true);
                    }
                    return false;
                }
                //get rekt minecraft
                //i'm doing it anyway
                //i dont care if theres snow in the way!!!!!!!
                //you dont own me!!!!
                state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(blockPos), ctx.playerRotations()), true)
                );
                // don't check selectedblock on this one, this is a fallback when we can't see any face directly, it's intended to be breaking the "incorrect" block
                state.setInput(Input.CLICK_LEFT, true);
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            logDebug(getClass().getSimpleName() + ": can't reach a block to break");
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
