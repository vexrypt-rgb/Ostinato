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
        BlockState state = stateAt(x, y, z);
        if (state == null) return BlockKind.UNKNOWN;
        if (!state.getFluidState().isEmpty()) return BlockKind.FLUID;
        if (state.isAir()) return BlockKind.AIR;
        return BlockKind.SOLID;
    }

    /**
     * Ostinato: run112. Placed maze blocks (concrete, planks, polished stone, cobble) so a prior run's carved room
     * can be told apart from a cave. Natural stone, dirt and ores are not structure.
     */
    @Override
    public boolean structure(int x, int y, int z) {
        BlockState state = stateAt(x, y, z);
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) return false;
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("concrete") || path.contains("planks") || path.contains("terracotta")
                || path.contains("glass") || path.contains("_wool") || path.contains("copper")
                || path.contains("brick") || path.contains("polished_") || path.contains("cobbled_")
                || path.equals("cobblestone") || path.contains("sandstone")
                || path.equals("torch") || path.equals("soul_torch") || path.equals("lantern") || path.equals("soul_lantern")
                || path.equals("chest") || path.equals("trapped_chest") || path.equals("barrel")
                || path.equals("decorated_pot") || path.equals("glowstone") || path.equals("sea_lantern")
                || path.equals("shroomlight") || path.contains("candle");
    }

    /** The server's block, or null when the column is not loaded. Client chunks lag the cave-guard fill (run43). */
    private BlockState stateAt(int x, int y, int z) {
        Level view = world;
        if (Minecraft.getInstance() != null && Minecraft.getInstance().getSingleplayerServer() != null) {
            ServerLevel overworld = Minecraft.getInstance().getSingleplayerServer().overworld();
            if (overworld != null) view = overworld;
        }
        if (view == null || y < view.getMinY() || y >= view.getMaxY()) return null;
        BlockPos pos = new BlockPos(x, y, z);
        if (!view.isLoaded(pos)) return null;
        return view.getBlockState(pos);
    }
}
