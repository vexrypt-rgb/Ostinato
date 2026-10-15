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

package baritone.gui.studio;

import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A studio volume as a schematic. Air is not part of the build, so the builder only places blocks. */
public final class OstinatoSchematic extends StaticSchematic {

    public OstinatoSchematic(BlockState[][][] states) {
        super(states);
    }

    public static OstinatoSchematic from(StudioVolume vol) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[][][] states = new BlockState[vol.sizeX()][vol.sizeZ()][vol.sizeY()];
        for (int x = 0; x < vol.sizeX(); x++) {
            for (int z = 0; z < vol.sizeZ(); z++) {
                for (int y = 0; y < vol.sizeY(); y++) {
                    states[x][z][y] = state(vol.get(x, y, z), air);
                }
            }
        }
        return new OstinatoSchematic(states);
    }

    public static OstinatoSchematic read(InputStream input) throws IOException {
        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        try {
            return from(StudioVolume.read(text));
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        if (!super.inSchematic(x, y, z, current)) {
            return false;
        }
        BlockState state = this.states[x][z][y];
        return state != null && !state.isAir();
    }

    private static BlockState state(int cell, BlockState air) {
        if (cell <= 0) {
            return air;
        }
        return BuiltInRegistries.BLOCK.get(Identifier.parse("minecraft:" + BlockPalette.name(cell)))
                .map(Holder.Reference::value)
                .orElse(Blocks.AIR)
                .defaultBlockState();
    }
}
