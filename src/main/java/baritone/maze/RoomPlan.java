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

/**
 * One room and its resealed corridor stub, in world coordinates. {@code roles[x][y][z]} is relative to the origin.
 */
public final class RoomPlan {

    public final int cellX;
    public final int cellZ;
    public final long mazeSeed;
    public final long roomSeed;
    public final MazeTheme theme;
    public final int originX;
    public final int originY;
    public final int originZ;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    public final BlockRole[][][] roles;

    public RoomPlan(int cellX, int cellZ, long mazeSeed, long roomSeed, MazeTheme theme,
                    int originX, int originY, int originZ, BlockRole[][][] roles) {
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.mazeSeed = mazeSeed;
        this.roomSeed = roomSeed;
        this.theme = theme;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.roles = roles;
        this.sizeX = roles.length;
        this.sizeY = roles[0].length;
        this.sizeZ = roles[0][0].length;
    }

    public boolean inRelative(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    public BlockRole role(int x, int y, int z) {
        return roles[x][y][z];
    }

    public RoomPlan withRole(int x, int y, int z, BlockRole role) {
        BlockRole[][][] copy = new BlockRole[sizeX][sizeY][sizeZ];
        for (int ix = 0; ix < sizeX; ix++) {
            for (int iy = 0; iy < sizeY; iy++) {
                System.arraycopy(roles[ix][iy], 0, copy[ix][iy], 0, sizeZ);
            }
        }
        copy[x][y][z] = role;
        return new RoomPlan(cellX, cellZ, mazeSeed, roomSeed, theme, originX, originY, originZ, copy);
    }

    /** Stable text of the theme and every role. Same seed and origin always produce the same text. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(theme.id).append(':').append(roomSeed).append(':');
        sb.append(originX).append(',').append(originY).append(',').append(originZ).append(':');
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    sb.append(roles[x][y][z].ordinal());
                }
            }
        }
        return sb.toString();
    }
}
