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

import java.util.ArrayList;
import java.util.List;

/**
 * Plans one sealed room and a short corridor stub. The stub is plugged: this pass does not dig a hole into a cave.
 * Shifts the room when the shell would touch air, fluid, or the surface, and skips cells that are already claimed.
 */
public final class MazePlanner {

    public static final int SIZE_X = 10;
    public static final int SIZE_Y = 5;
    public static final int SIZE_Z = 7;
    public static final int SEARCH_RADIUS = 3;

    /** Interior spots a feature or a container may occupy. All of them start as air. */
    public static final int[][] LOOT_SPOTS = {{2, 2, 2}, {4, 2, 2}, {2, 2, 4}, {4, 2, 4}};

    /**
     * Tried in order. Horizontal shifts stay inside the cell. Downward shifts are preferred over upward ones:
     * the maze is supposed to sit under solid ground.
     */
    private static final int[][] SHIFTS = {
            {0, 0, 0},
            {2, 0, 0}, {-2, 0, 0}, {0, 0, 2}, {0, 0, -2}, {4, 0, 0}, {0, 0, 4},
            {0, -6, 0}, {2, -6, 0}, {-2, -6, 0}, {0, -6, 2}, {0, -6, -2},
            {0, -12, 0}, {2, -12, 0}, {0, -12, 2},
            {0, -18, 0}, {0, -24, 0},
            {0, 6, 0}
    };

    private MazePlanner() {}

    public interface CellClaims {
        boolean claimed(int cellX, int cellZ);
    }

    /** A placement attempt. {@link #refusal} is null when the shell passed the seal check. */
    public static final class Attempt {
        public final int cellX;
        public final int cellZ;
        public final RoomPlan plan;
        public final ShellSeal.SealVerdict verdict;
        public final boolean shifted;
        public final int tries;
        public final String refusal;

        Attempt(int cellX, int cellZ, RoomPlan plan, ShellSeal.SealVerdict verdict, boolean shifted, int tries, String refusal) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.plan = plan;
            this.verdict = verdict;
            this.shifted = shifted;
            this.tries = tries;
            this.refusal = refusal;
        }

        public boolean ok() {
            return refusal == null;
        }

        static Attempt refused(int cellX, int cellZ, String reason) {
            return new Attempt(cellX, cellZ, null, null, false, 0, reason);
        }
    }

    public record Cell(int x, int z) {}

    public static List<Cell> spiral(int cellX, int cellZ, int radius) {
        List<Cell> out = new ArrayList<>();
        out.add(new Cell(cellX, cellZ));
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                out.add(new Cell(cellX + dx, cellZ - r));
                out.add(new Cell(cellX + dx, cellZ + r));
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                out.add(new Cell(cellX - r, cellZ + dz));
                out.add(new Cell(cellX + r, cellZ + dz));
            }
        }
        return out;
    }

    /**
     * The room geometry for one origin. Does not look at the world. The corridor runs toward +X and ends in a cap.
     */
    public static RoomPlan layout(long mazeSeed, int cellX, int cellZ, int originX, int originY, int originZ) {
        long roomSeed = MazeSeed.roomSeed(mazeSeed, cellX, cellZ);
        MazeTheme theme = MazeTheme.forCell(mazeSeed, cellX, cellZ);
        BlockRole[][][] roles = new BlockRole[SIZE_X][SIZE_Y][SIZE_Z];
        for (int x = 0; x < SIZE_X; x++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    roles[x][y][z] = BlockRole.OUTSIDE;
                }
            }
        }
        for (int x = 0; x <= 6; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z <= 6; z++) {
                    boolean edgeX = x == 0 || x == 6;
                    boolean edgeZ = z == 0 || z == 6;
                    if (y == 0) {
                        roles[x][y][z] = BlockRole.SHELL;
                    } else if (y == 4) {
                        roles[x][y][z] = BlockRole.CEILING;
                    } else if (edgeX || edgeZ) {
                        roles[x][y][z] = BlockRole.WALL;
                    } else if (y == 1) {
                        roles[x][y][z] = BlockRole.FLOOR;
                    } else {
                        roles[x][y][z] = BlockRole.AIR;
                    }
                }
            }
        }
        for (int x = 6; x <= 8; x++) {
            roles[x][2][3] = BlockRole.AIR;
            roles[x][3][3] = BlockRole.AIR;
        }
        for (int x = 7; x <= 9; x++) {
            roles[x][1][3] = BlockRole.FLOOR;
            roles[x][4][3] = BlockRole.CEILING;
            roles[x][2][2] = BlockRole.SHELL;
            roles[x][3][2] = BlockRole.SHELL;
            roles[x][2][4] = BlockRole.SHELL;
            roles[x][3][4] = BlockRole.SHELL;
        }
        roles[9][2][3] = BlockRole.STUB_CAP;
        roles[9][3][3] = BlockRole.STUB_CAP;
        roles[3][3][3] = BlockRole.LIGHT;
        int spot = (int) Long.remainderUnsigned(roomSeed, LOOT_SPOTS.length);
        int[] feature = LOOT_SPOTS[spot];
        roles[feature[0]][feature[1]][feature[2]] = BlockRole.FEATURE;
        return new RoomPlan(cellX, cellZ, mazeSeed, roomSeed, theme, originX, originY, originZ, roles);
    }

    /**
     * Places one room in {@code cellX, cellZ}, shifting inside the cell until the shell is sealed and covered.
     * {@code maxY} is exclusive, matching a Minecraft world.
     */
    public static Attempt place(long mazeSeed, int cellX, int cellZ, int preferredY, int minY, int maxY, BlockProbe probe) {
        int baseX = MazeSeed.originX(cellX);
        int baseZ = MazeSeed.originZ(cellZ);
        Attempt last = null;
        int tried = 0;
        for (int[] shift : SHIFTS) {
            int ox = baseX + shift[0];
            int oy = preferredY + shift[1];
            int oz = baseZ + shift[2];
            if (oy < minY || oy + SIZE_Y + MazeSeed.COVER >= maxY) {
                continue;
            }
            if (MazeSeed.cellOf(ox) != cellX || MazeSeed.cellOf(ox + SIZE_X - 1) != cellX) {
                continue;
            }
            if (MazeSeed.cellOf(oz) != cellZ || MazeSeed.cellOf(oz + SIZE_Z - 1) != cellZ) {
                continue;
            }
            tried++;
            RoomPlan plan = layout(mazeSeed, cellX, cellZ, ox, oy, oz);
            ShellSeal.SealVerdict verdict = ShellSeal.check(plan, probe);
            boolean shifted = shift[0] != 0 || shift[1] != 0 || shift[2] != 0;
            Attempt attempt = new Attempt(cellX, cellZ, plan, verdict, shifted, tried, verdict.ok() ? null : verdict.reason);
            if (attempt.ok()) {
                return attempt;
            }
            last = attempt;
        }
        if (last == null) {
            return Attempt.refused(cellX, cellZ, "no shift fit in the world height");
        }
        return last;
    }

    /**
     * The nearest unclaimed cell, within {@code radius}, whose room can be sealed. Claims are not written here.
     */
    public static Attempt placeOpen(long mazeSeed, int nearX, int nearZ, int preferredY, int minY, int maxY,
                                    BlockProbe probe, CellClaims claims, int radius) {
        Attempt last = null;
        boolean anyOpen = false;
        for (Cell cell : spiral(nearX, nearZ, radius)) {
            if (claims != null && claims.claimed(cell.x, cell.z)) {
                continue;
            }
            anyOpen = true;
            Attempt attempt = place(mazeSeed, cell.x, cell.z, preferredY, minY, maxY, probe);
            if (attempt.ok()) {
                return attempt;
            }
            last = attempt;
        }
        if (!anyOpen) {
            return Attempt.refused(nearX, nearZ, "every nearby cell is already claimed");
        }
        if (last == null) {
            return Attempt.refused(nearX, nearZ, "no shift fit in the world height");
        }
        return new Attempt(last.cellX, last.cellZ, last.plan, last.verdict, last.shifted, last.tries,
                last.refusal + " (no open sealed cell within " + radius + ")");
    }
}
