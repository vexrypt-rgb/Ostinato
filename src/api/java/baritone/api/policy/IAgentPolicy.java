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
package baritone.api.policy;

import net.minecraft.util.math.BlockPos;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Ostinato: policy hooks a driving mod (TenorClef) injects into pathing, mining and building. One global
 * instance, read from {@link baritone.api.IBaritone#getAgentPolicy()}. Lists are live: add or remove under the
 * matching mutex. Implemented today by the internal {@code baritone.altoclef.AltoClefSettings}.
 */
public interface IAgentPolicy {

    // Breaking
    void avoidBlockBreak(BlockPos pos);
    void avoidBlockBreak(Predicate<BlockPos> avoider);
    boolean shouldAvoidBreaking(BlockPos pos);
    boolean shouldAvoidBreaking(int x, int y, int z);
    Set<BlockPos> getBlocksToAvoidBreaking();
    List<Predicate<BlockPos>> getBreakAvoiders();
    Object getBreakMutex();

    // Placing
    void avoidBlockPlace(Predicate<BlockPos> avoider);
    boolean shouldAvoidPlacingAt(BlockPos pos);
    boolean shouldAvoidPlacingAt(int x, int y, int z);
    List<Predicate<BlockPos>> getPlaceAvoiders();
    Object getPlaceMutex();

    // Walking
    void canWalkOnEndPortal(boolean canWalk);
    boolean isCanWalkOnEndPortal();
    boolean canWalkOnForce(int x, int y, int z);
    boolean shouldAvoidWalkThroughForce(BlockPos pos);
    boolean shouldAvoidWalkThroughForce(int x, int y, int z);
    List<Predicate<BlockPos>> getForceWalkOnPredicates();
    List<Predicate<BlockPos>> getForceAvoidWalkThroughPredicates();

    // Tools and items
    boolean shouldForceSaveTool(BlockState state, ItemStack tool);
    boolean shouldForceUseTool(BlockState state, ItemStack tool);
    List<BiPredicate<BlockState, ItemStack>> getForceSaveToolPredicates();
    List<BiPredicate<BlockState, ItemStack>> getForceUseToolPredicates();
    boolean isItemProtected(Item item);
    Set<Item> getProtectedItems();
    void protectItem(Item item);
    void stopProtectingItem(Item item);

    // Heuristics
    double applyGlobalHeuristic(double prev, int x, int y, int z);
    List<BiFunction<Double, BlockPos, Double>> getGlobalHeuristics();
    Object getGlobalHeuristicMutex();

    // Fluids, buckets, interaction
    void configurePlaceBucketButDontFall(boolean allow);
    boolean shouldNotPlaceBucketButStillFall();
    void treatSoulSandAsOrdinaryBlock(boolean enable);
    boolean shouldTreatSoulSandAsOrdinaryBlock();
    boolean isInteractionPaused();
    void setInteractionPaused(boolean paused);
    boolean isFlowingWaterPassAllowed();
    void setFlowingWaterPass(boolean pass);
    boolean canSwimThroughLava();
    void allowSwimThroughLava(boolean allow);
    Object getPropertiesMutex();
}
