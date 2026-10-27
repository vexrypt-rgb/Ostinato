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

package baritone.maze;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Reads the loaded world into {@link BlockProbe}. Unloaded blocks are unknown, which fails a seal check. */
public final class MazeWorldProbe implements BlockProbe {

    private final Level world;

    public MazeWorldProbe(Level world) {
        this.world = world;
    }

    @Override
    public BlockKind get(int x, int y, int z) {
        // Ostinato: the cave-guard fill is applied on the server. The client chunk can still show
        // the old cave, so a seal check then refuses every cell (run43).
        Level view = world;
        if (Minecraft.getInstance() != null && Minecraft.getInstance().getSingleplayerServer() != null) {
            ServerLevel overworld = Minecraft.getInstance().getSingleplayerServer().overworld();
            if (overworld != null) view = overworld;
        }
        if (view == null || y < view.getMinY() || y >= view.getMaxY()) {
            return BlockKind.UNKNOWN;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!view.isLoaded(pos)) {
            return BlockKind.UNKNOWN;
        }
        BlockState state = view.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return BlockKind.FLUID;
        }
        if (state.isAir()) {
            return BlockKind.AIR;
        }
        return BlockKind.SOLID;
    }
}
