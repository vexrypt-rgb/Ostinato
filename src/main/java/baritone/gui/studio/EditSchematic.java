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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cells that differ from a captured original. Air is included only when the caller asks,
 * so a freecam edit can break blocks and a superflat canvas does not dig the real ground.
 */
public final class EditSchematic extends StaticSchematic {

    private final boolean[][][] mask;
    private final int changes;

    private EditSchematic(BlockState[][][] states, boolean[][][] mask, int changes) {
        super(states);
        this.mask = mask;
        this.changes = changes;
    }

    public int changes() {
        return changes;
    }

    public static EditSchematic diff(StudioVolume edited, StudioVolume original, boolean includeAir) {
        if (edited == null || original == null
                || edited.sizeX() != original.sizeX()
                || edited.sizeY() != original.sizeY()
                || edited.sizeZ() != original.sizeZ()) {
            return null;
        }
        int sx = edited.sizeX();
        int sy = edited.sizeY();
        int sz = edited.sizeZ();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[][][] states = new BlockState[sx][sz][sy];
        boolean[][][] mask = new boolean[sx][sz][sy];
        int changes = 0;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                for (int y = 0; y < sy; y++) {
                    int now = edited.get(x, y, z);
                    int was = original.get(x, y, z);
                    states[x][z][y] = air;
                    if (now == was) {
                        continue;
                    }
                    if (now == 0 && !includeAir) {
                        continue;
                    }
                    BlockState state = now == 0 ? air : BlockPalette.stateOf(now);
                    if (state == null) {
                        continue;
                    }
                    states[x][z][y] = state;
                    mask[x][z][y] = true;
                    changes++;
                }
            }
        }
        return new EditSchematic(states, mask, changes);
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState current) {
        return super.inSchematic(x, y, z, current) && mask[x][z][y];
    }
}
