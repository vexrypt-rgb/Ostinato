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

package baritone.altoclef;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public class AltoClefSettings {

    // woo singletons
    private static AltoClefSettings _instance = new AltoClefSettings();
    private final Object breakMutex = new Object();
    private final Object placeMutex = new Object();
    private final Object propertiesMutex = new Object();
    private final Object globalHeuristicMutex = new Object();
    private final HashSet<BlockPos> _blocksToAvoidBreaking = new HashSet<>();
    private final List<Predicate<BlockPos>> _breakAvoiders = new ArrayList<>();
    private final List<Predicate<BlockPos>> _placeAvoiders = new ArrayList<>();
    private final List<Predicate<BlockPos>> _forceCanWalkOn = new ArrayList<>();
    private final List<Predicate<BlockPos>> _forceAvoidWalkThrough = new ArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> _forceSaveTool = new ArrayList<>();
    private final List<BiPredicate<BlockState, ItemStack>> _forceUseTool = new ArrayList<>();
    private final List<BiFunction<Double, BlockPos, Double>> _globalHeuristics = new ArrayList<>();
    private final HashSet<Item> _protectedItems = new HashSet<>();
    private boolean _allowFlowingWaterPass;
    private boolean _pauseInteractions;
    private boolean _dontPlaceBucketButStillFall;
    private boolean _allowSwimThroughLava = false;
    private boolean _treatSoulSandAsOrdinaryBlock = false;
    private boolean canWalkOnEndPortal = false;

    public static AltoClefSettings getInstance() {
        return _instance;
    }

    public void canWalkOnEndPortal(boolean canWalk) {
        canWalkOnEndPortal = canWalk;
    }

    public void avoidBlockBreak(BlockPos pos) {
        synchronized (breakMutex) {
            _blocksToAvoidBreaking.add(pos);
        }
    }

    public void avoidBlockBreak(Predicate<BlockPos> avoider) {
        synchronized (breakMutex) {
            _breakAvoiders.add(avoider);
        }
    }

    public void configurePlaceBucketButDontFall(boolean allow) {
        synchronized (propertiesMutex) {
            _dontPlaceBucketButStillFall = allow;
        }
    }

    public void treatSoulSandAsOrdinaryBlock(boolean enable) {
        synchronized (propertiesMutex) {
            _treatSoulSandAsOrdinaryBlock = enable;
        }
    }

    public void avoidBlockPlace(Predicate<BlockPos> avoider) {
        synchronized (placeMutex) {
            _placeAvoiders.add(avoider);
        }
    }

    public boolean shouldForceSaveTool(BlockState state, ItemStack tool) {
        synchronized (propertiesMutex) {
            for (BiPredicate<BlockState, ItemStack> pred : _forceSaveTool) {
                if (pred.test(state, tool)) return true;
            }
            return false;
        }
    }

    // The x,y,z forms are asked per block by the pathfinder: with nothing registered they answer without a BlockPos.

    public boolean shouldAvoidBreaking(int x, int y, int z) {
        synchronized (breakMutex) {
            if (_blocksToAvoidBreaking.isEmpty() && _breakAvoiders.isEmpty()) return false;
            return shouldAvoidBreaking(new BlockPos(x, y, z));
        }
    }

    public boolean shouldAvoidBreaking(BlockPos pos) {
        synchronized (breakMutex) {
            if (_blocksToAvoidBreaking.contains(pos))
                return true;
            return anyMatch(_breakAvoiders, pos);
        }
    }

    public boolean shouldAvoidPlacingAt(BlockPos pos) {
        synchronized (placeMutex) {
            return anyMatch(_placeAvoiders, pos);
        }
    }

    public boolean shouldAvoidPlacingAt(int x, int y, int z) {
        synchronized (placeMutex) {
            return !_placeAvoiders.isEmpty() && anyMatch(_placeAvoiders, new BlockPos(x, y, z));
        }
    }

    public boolean canWalkOnForce(int x, int y, int z) {
        synchronized (propertiesMutex) {
            return !_forceCanWalkOn.isEmpty() && anyMatch(_forceCanWalkOn, new BlockPos(x, y, z));
        }
    }

    public boolean shouldAvoidWalkThroughForce(BlockPos pos) {
        synchronized (propertiesMutex) {
            return anyMatch(_forceAvoidWalkThrough, pos);
        }
    }

    public boolean shouldAvoidWalkThroughForce(int x, int y, int z) {
        synchronized (propertiesMutex) {
            return !_forceAvoidWalkThrough.isEmpty() && anyMatch(_forceAvoidWalkThrough, new BlockPos(x, y, z));
        }
    }

    private static boolean anyMatch(List<Predicate<BlockPos>> preds, BlockPos pos) {
        for (Predicate<BlockPos> pred : preds) {
            if (pred.test(pos)) return true;
        }
        return false;
    }

    /**
     * The walk-on and walk-through hooks as they stand, for a path calculation that asks per block and cannot take
     * the lock each time. Null while none is registered.
     */
    public List<Predicate<BlockPos>> forceWalkOnSnapshot() {
        synchronized (propertiesMutex) {
            return _forceCanWalkOn.isEmpty() ? null : new ArrayList<>(_forceCanWalkOn);
        }
    }

    public List<Predicate<BlockPos>> forceAvoidWalkThroughSnapshot() {
        synchronized (propertiesMutex) {
            return _forceAvoidWalkThrough.isEmpty() ? null : new ArrayList<>(_forceAvoidWalkThrough);
        }
    }

    public boolean shouldForceUseTool(BlockState state, ItemStack tool) {
        synchronized (propertiesMutex) {
            for (BiPredicate<BlockState, ItemStack> pred : _forceUseTool) {
                if (pred.test(state, tool)) return true;
            }
            return false;
        }
    }

    public boolean shouldNotPlaceBucketButStillFall() {
        synchronized (propertiesMutex) {
            return _dontPlaceBucketButStillFall;
        }
    }

    public boolean shouldTreatSoulSandAsOrdinaryBlock() {
        synchronized (propertiesMutex) {
            return _treatSoulSandAsOrdinaryBlock;
        }
    }

    public boolean isInteractionPaused() {
        synchronized (propertiesMutex) {
            return _pauseInteractions;
        }
    }

    public void setInteractionPaused(boolean paused) {
        synchronized (propertiesMutex) {
            _pauseInteractions = paused;
        }
    }

    public boolean isFlowingWaterPassAllowed() {
        synchronized (propertiesMutex) {
            return _allowFlowingWaterPass;
        }
    }

    public boolean canSwimThroughLava() {
        synchronized (propertiesMutex) {
            return _allowSwimThroughLava;
        }
    }

    public void setFlowingWaterPass(boolean pass) {
        synchronized (propertiesMutex) {
            _allowFlowingWaterPass = pass;
        }
    }

    public void allowSwimThroughLava(boolean allow) {
        synchronized (propertiesMutex) {
            _allowSwimThroughLava = allow;
        }
    }

    public double applyGlobalHeuristic(double prev, int x, int y, int z) {
        return prev;
        /*
        synchronized (globalHeuristicMutex) {
            BlockPos p = new BlockPos(x, y, z);
            for (BiFunction<Double, BlockPos, Double> toApply : _globalHeuristics) {
                prev = toApply.apply(prev, p);
            }
        }
        return prev;
         */
    }

    public HashSet<BlockPos> getBlocksToAvoidBreaking() {
        return _blocksToAvoidBreaking;
    }

    public List<Predicate<BlockPos>> getBreakAvoiders() {
        return _breakAvoiders;
    }

    public List<Predicate<BlockPos>> getPlaceAvoiders() {
        return _placeAvoiders;
    }

    public List<Predicate<BlockPos>> getForceWalkOnPredicates() {
        return _forceCanWalkOn;
    }

    public List<Predicate<BlockPos>> getForceAvoidWalkThroughPredicates() {
        return _forceAvoidWalkThrough;
    }

    public List<BiPredicate<BlockState, ItemStack>> getForceSaveToolPredicates() {
        return _forceSaveTool;
    }

    public List<BiPredicate<BlockState, ItemStack>> getForceUseToolPredicates() {
        return _forceUseTool;
    }

    public List<BiFunction<Double, BlockPos, Double>> getGlobalHeuristics() {
        return _globalHeuristics;
    }

    public boolean isItemProtected(Item item) {
        return _protectedItems.contains(item);
    }

    public HashSet<Item> getProtectedItems() {
        return _protectedItems;
    }

    public void protectItem(Item item) {
        _protectedItems.add(item);
    }

    public void stopProtectingItem(Item item) {
        _protectedItems.remove(item);
    }

    public Object getBreakMutex() {
        return breakMutex;
    }

    public Object getPlaceMutex() {
        return placeMutex;
    }

    public Object getPropertiesMutex() {
        return propertiesMutex;
    }

    public Object getGlobalHeuristicMutex() {
        return globalHeuristicMutex;
    }

    public boolean isCanWalkOnEndPortal() {
        return canWalkOnEndPortal;
    }
}