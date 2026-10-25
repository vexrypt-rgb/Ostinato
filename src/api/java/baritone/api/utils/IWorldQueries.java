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

package baritone.api.utils;

import baritone.api.pathing.movement.IMovement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;

import java.util.List;

/**
 * Ostinato: read-only world questions a driver asks with the same rules the pathfinder uses, so it
 * does not need MovementHelper, ToolSet, BlockStateInterface, CalculationContext or MineProcess.
 * Get it from {@code IBaritone.getWorldQueries()}.
 */
public interface IWorldQueries {

    /** @return true if the state is water (source or flowing), as the pathfinder sees it */
    boolean isWater(BlockState state);

    /** @return true if the state is lava (source or flowing), as the pathfinder sees it */
    boolean isLava(BlockState state);

    /** @return true if a block can be placed against the block at {@code pos} */
    boolean canPlaceAgainst(BlockPos pos);

    /** @return true if the pathfinder refuses to break the block at {@code pos} (e.g. it would release a fluid) */
    boolean avoidBreaking(BlockPos pos);

    /** @return true if mining could plausibly break the block at {@code pos} (not air, bedrock or a fluid) */
    boolean plausibleToBreak(BlockPos pos);

    /** @return the mining speed of {@code tool} against {@code state}, using Baritone's tool rules (higher is faster) */
    double toolSpeedVsBlock(ItemStack tool, BlockState state);

    /** @return the blocks this path movement still has to break */
    List<BlockPos> blocksToBreak(IMovement movement);

    /** @return the blocks this path movement still has to place */
    List<BlockPos> blocksToPlace(IMovement movement);
}
