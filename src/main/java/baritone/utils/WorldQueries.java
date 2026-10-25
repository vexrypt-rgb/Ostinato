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

package baritone.utils;

import baritone.Baritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.IWorldQueries;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.process.MineProcess;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;

import java.util.Collections;
import java.util.List;

/** Ostinato: {@link IWorldQueries} over the pathfinder's own helpers. No behaviour of its own. */
public final class WorldQueries implements IWorldQueries {

    private final Baritone baritone;

    public WorldQueries(Baritone baritone) {
        this.baritone = baritone;
    }

    private BlockStateInterface bsi() {
        return new BlockStateInterface(baritone.getPlayerContext());
    }

    @Override
    public boolean isWater(BlockState state) {
        return MovementHelper.isWater(state);
    }

    @Override
    public boolean isLava(BlockState state) {
        return MovementHelper.isLava(state);
    }

    @Override
    public boolean canPlaceAgainst(BlockPos pos) {
        return MovementHelper.canPlaceAgainst(baritone.getPlayerContext(), pos);
    }

    @Override
    public boolean avoidBreaking(BlockPos pos) {
        BlockStateInterface bsi = bsi();
        return MovementHelper.avoidBreaking(bsi, pos.getX(), pos.getY(), pos.getZ(), bsi.get0(pos));
    }

    @Override
    public boolean plausibleToBreak(BlockPos pos) {
        return MineProcess.plausibleToBreak(new CalculationContext(baritone), pos);
    }

    @Override
    public double toolSpeedVsBlock(ItemStack tool, BlockState state) {
        return ToolSet.calculateSpeedVsBlock(tool, state);
    }

    @Override
    public List<BlockPos> blocksToBreak(IMovement movement) {
        return movement instanceof Movement ? ((Movement) movement).toBreak(bsi()) : Collections.emptyList();
    }

    @Override
    public List<BlockPos> blocksToPlace(IMovement movement) {
        return movement instanceof Movement ? ((Movement) movement).toPlace(bsi()) : Collections.emptyList();
    }
}
