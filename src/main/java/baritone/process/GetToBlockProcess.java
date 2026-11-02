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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.*;
import baritone.api.process.IGetToBlockProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.cadenza.Cadenza;
import baritone.pathing.cadenza.CadenzaSession;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class GetToBlockProcess extends BaritoneProcessHelper implements IGetToBlockProcess {

    private BlockOptionalMeta gettingTo;
    private List<BlockPos> knownLocations;
    private List<BlockPos> blacklist; // locations we failed to calc to
    private BlockPos start;

    private int tickCount = 0;
    private int arrivalTickCount = 0;
    /** Ostinato Cadenza: positioning memo and the open in progress (used only with the cadenza setting). */
    private final CadenzaSession cadenza = new CadenzaSession();
    private BetterBlockPos debugCell;

    public GetToBlockProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void getToBlock(BlockOptionalMeta block) {
        onLostControl();
        gettingTo = block;
        start = ctx.playerFeet();
        blacklist = new ArrayList<>();
        arrivalTickCount = 0;
        rescan(new ArrayList<>(), new GetToBlockCalculationContext(false));
    }

    @Override
    public boolean isActive() {
        return gettingTo != null;
    }

    @Override
    public synchronized PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (knownLocations == null) {
            rescan(new ArrayList<>(), new GetToBlockCalculationContext(false));
        }
        if (knownLocations.isEmpty()) {
            if (Baritone.settings().exploreForBlocks.value && !calcFailed) {
                return new PathingCommand(new GoalRunAway(1, start) {
                    @Override
                    public boolean isInGoal(int x, int y, int z) {
                        return false;
                    }

                    @Override
                    public double heuristic() {
                        return Double.NEGATIVE_INFINITY;
                    }
                }, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            }
            logDirect("No known locations of " + gettingTo + ", canceling GetToBlock");
            if (isSafeToCancel) {
                onLostControl();
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Goal goal = Baritone.settings().cadenza.value && rightClickOnArrival(gettingTo.getBlock()) ? cadenzaGoal()
                : new GoalComposite(knownLocations.stream().map(this::createGoal).toArray(Goal[]::new));
        if (calcFailed) {
            if (Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + gettingTo + ", blacklisting presumably unreachable closest instances...");
                blacklistClosest();
                return onTick(false, isSafeToCancel); // gamer moment
            } else {
                logDirect("Unable to find any path to " + gettingTo + ", canceling GetToBlock");
                if (isSafeToCancel) {
                    onLostControl();
                }
                return new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            List<BlockPos> current = new ArrayList<>(knownLocations);
            CalculationContext context = new GetToBlockCalculationContext(true);
            Baritone.getExecutor().execute(() -> rescan(current, context));
        }
        if (goal.isInGoal(ctx.playerFeet()) && goal.isInGoal(baritone.getPathingBehavior().pathStart()) && isSafeToCancel) {
            // we're there
            if (rightClickOnArrival(gettingTo.getBlock())) {
                if (rightClick()) {
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            } else {
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    // blacklist the closest block and its adjacent blocks
    public synchronized boolean blacklistClosest() {
        List<BlockPos> newBlacklist = new ArrayList<>();
        knownLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(newBlacklist::add);
        outer:
        while (true) {
            for (BlockPos known : knownLocations) {
                for (BlockPos blacklist : newBlacklist) {
                    if (areAdjacent(known, blacklist)) { // directly adjacent
                        newBlacklist.add(known);
                        knownLocations.remove(known);
                        continue outer;
                    }
                }
            }
            // i can't do break; (codacy gets mad), and i can't do if(true){break}; (codacy gets mad)
            // so i will do this
            switch (newBlacklist.size()) {
                default:
                    break outer;
            }
        }
        logDebug("Blacklisting unreachable locations " + newBlacklist);
        blacklist.addAll(newBlacklist);
        return !newBlacklist.isEmpty();
    }

    // this is to signal to MineProcess that we don't care about the allowBreak setting
    // it is NOT to be used to actually calculate a path
    public class GetToBlockCalculationContext extends CalculationContext {

        public GetToBlockCalculationContext(boolean forUseOnAnotherThread) {
            super(GetToBlockProcess.super.baritone, forUseOnAnotherThread);
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return 1;
        }
    }

    // safer than direct double comparison from distanceSq
    private boolean areAdjacent(BlockPos posA, BlockPos posB) {
        int diffX = Math.abs(posA.getX() - posB.getX());
        int diffY = Math.abs(posA.getY() - posB.getY());
        int diffZ = Math.abs(posA.getZ() - posB.getZ());
        return (diffX + diffY + diffZ) == 1;
    }

    @Override
    public synchronized void onLostControl() {
        gettingTo = null;
        knownLocations = null;
        start = null;
        blacklist = null;
        cadenza.reset();
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        if (knownLocations.isEmpty()) {
            return "Exploring randomly to find " + gettingTo + ", no known locations";
        }
        return "Get To " + gettingTo + ", " + knownLocations.size() + " known locations";
    }

    private synchronized void rescan(List<BlockPos> known, CalculationContext context) {
        List<BlockPos> positions = MineProcess.searchWorld(context, new BlockOptionalMetaLookup(gettingTo), 64, known, blacklist, Collections.emptyList());
        positions.removeIf(blacklist::contains);
        knownLocations = positions;
    }

    private Goal createGoal(BlockPos pos) {
        if (walkIntoInsteadOfAdjacent(gettingTo.getBlock())) {
            return new GoalTwoBlocks(pos);
        }
        if (blockOnTopMustBeRemoved(gettingTo.getBlock()) && MovementHelper.isBlockNormalCube(baritone.bsi.get0(pos.above()))) { // TODO this should be the check for chest openability
            return new GoalBlock(pos.above());
        }
        return new GoalGetToBlock(pos);
    }

    /**
     * Ostinato Cadenza: stand where the nearest containers can actually be opened from (reach and line of sight)
     * instead of merely next to them. A chest under a solid block keeps its old goal (the block must go first), as does
     * any container Cadenza finds no cell for and every one past cadenzaMaxTargets.
     */
    private Goal cadenzaGoal() {
        CalculationContext context = new GetToBlockCalculationContext(false);
        double reach = ctx.playerController().getBlockReachDistance();
        double eye = ctx.player().getEyeHeight(Pose.STANDING);
        int max = Baritone.settings().cadenzaMaxTargets.value;
        BetterBlockPos feet = ctx.playerFeet();
        List<BlockPos> byDistance = new ArrayList<>(knownLocations);
        byDistance.sort(Comparator.comparingDouble(feet::distSqr));
        List<Cadenza.Target> targets = new ArrayList<>();
        List<Goal> extras = new ArrayList<>();
        for (BlockPos pos : byDistance) {
            boolean covered = blockOnTopMustBeRemoved(gettingTo.getBlock()) && MovementHelper.isBlockNormalCube(baritone.bsi.get0(pos.above()));
            if (!covered && targets.size() < max) {
                targets.add(new Cadenza.Target(Cadenza.Kind.USE, pos, reach, eye, false));
            } else {
                extras.add(createGoal(pos));
            }
        }
        return cadenza.goal(context, targets, Cadenza.Forbidden.NONE, Baritone.settings().cadenzaShortHorizon.value, r -> {
            if (Baritone.settings().cadenzaDebug.value) {
                logDirect("Cadenza: no cell for " + r.target + " (" + r.status + "), old goal kept");
            }
            extras.add(createGoal(r.target.pos));
        }, extras);
    }

    private boolean rightClick() {
        boolean useCadenza = Baritone.settings().cadenza.value;
        for (BlockPos pos : knownLocations) {
            Optional<Rotation> reachable = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
            if (reachable.isPresent()) {
                baritone.getLookBehavior().updateTarget(reachable.get(), true);
                if (useCadenza) {
                    cadenza.attempt(pos, 20);
                }
                if (knownLocations.contains(ctx.getSelectedBlock().orElse(null))) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true); // TODO find some way to right click even if we're in an ESC menu
                    System.out.println(ctx.player().containerMenu);
                    if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
                        if (useCadenza) {
                            cadenza.confirm(); // the container is open
                        }
                        return true;
                    }
                }
                if (arrivalTickCount++ > 20) {
                    logDirect("Right click timed out");
                    if (useCadenza) {
                        cadenza.fail();
                    }
                    return true;
                }
                return false; // trying to right click, will do it next tick or so
            }
        }
        if (useCadenza && cadenza.inCandidate(ctx.playerFeet())) {
            // the planned cell does not work live: drop it after a few ticks and replan, instead of giving up here
            if (Baritone.settings().cadenzaDebug.value && !ctx.playerFeet().equals(debugCell)) {
                debugCell = ctx.playerFeet();
                logDirect("Cadenza: cannot reach " + knownLocations + " from " + debugCell + " eye " + ctx.player().getEyePosition(1.0F));
            }
            cadenza.nothingReachable(ctx.playerFeet(), ctx.player().onGround());
            return false;
        }
        logDirect("Arrived but failed to right click open");
        return true;
    }

    private boolean walkIntoInsteadOfAdjacent(Block block) {
        if (!Baritone.settings().enterPortal.value) {
            return false;
        }
        return block == Blocks.NETHER_PORTAL;
    }

    private boolean rightClickOnArrival(Block block) {
        if (!Baritone.settings().rightClickContainerOnArrival.value) {
            return false;
        }
        return block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    private boolean blockOnTopMustBeRemoved(Block block) {
        if (!rightClickOnArrival(block)) { // only if we plan to actually open it on arrival
            return false;
        }
        // only these chests; you can open a crafting table or furnace even with a block on top
        return block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }
}
