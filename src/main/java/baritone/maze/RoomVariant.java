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
import java.util.Locale;

/**
 * Ostinato: 2026-11-02, backrooms variants. Room shapes and sizes for {@code #maze.backrooms.connected} (forever
 * expand). Every variant fits one {@link ConnectedPlan#PITCH} cell with room for a tunnel, and its interior covers the
 * door lane (cell-relative columns 2..4 on both axes), so a door in any wall lines up with the neighbour's door.
 * <p>
 * A variant is a pure function of the maze seed and the cell ({@link #forCell}), so bots that never talk agree. It is
 * also written into the connected map ({@code room ... <id>}) so a map saved before variants existed keeps loading as
 * {@link #CLASSIC}, the 7x7x7 box every older room was dug as.
 */
public enum RoomVariant {
    // id, x, height, z, offX, offZ, shape, weight, themes (theme, weight)...
    CLASSIC("classic", 7, 7, 7, 0, 0, Shape.BOX, 0),
    SMALL_OFFICE("small_office", 5, 7, 5, 1, 1, Shape.BOX, 12, MazeTheme.YELLOW_OFFICE, 70, MazeTheme.PIPES, 30),
    MEDIUM_OFFICE("medium_office", 7, 7, 7, 0, 0, Shape.BOX, 13, MazeTheme.YELLOW_OFFICE, 60, MazeTheme.HUB, 25, MazeTheme.DEEP, 15),
    LARGE_OFFICE("large_office", 11, 7, 11, 0, 0, Shape.BOX, 10, MazeTheme.YELLOW_OFFICE, 50, MazeTheme.MONO_YELLOW, 50),
    LONG_HALL_X("long_hall_x", 13, 7, 5, 0, 1, Shape.BOX, 7, MazeTheme.MONO_YELLOW, 100),
    LONG_HALL_Z("long_hall_z", 5, 7, 13, 1, 0, Shape.BOX, 7, MazeTheme.MONO_YELLOW, 100),
    L_OFFICE("l_office", 11, 7, 11, 0, 0, Shape.L, 10, MazeTheme.YELLOW_OFFICE, 60, MazeTheme.HUB, 40),
    LOBBY("lobby", 11, 9, 11, 0, 0, Shape.BOX, 8, MazeTheme.LOBBY, 100),
    POOLROOM("poolroom", 11, 8, 11, 0, 0, Shape.POOL, 8, MazeTheme.POOLROOMS, 100),
    MAINTENANCE("maintenance", 6, 8, 11, 0, 0, Shape.PIPES, 13, MazeTheme.MAINTENANCE, 80, MazeTheme.PIPES, 20),
    WAREHOUSE("warehouse", 13, 9, 13, 0, 0, Shape.PILLARS, 7, MazeTheme.WAREHOUSE, 100),
    HUB_SQUARE("hub_square", 9, 7, 9, 0, 0, Shape.BOX, 5, MazeTheme.HUB, 100);

    /** Overlays on the plain box. */
    public enum Shape { BOX, L, POOL, PIPES, PILLARS }

    /** Cell-relative door lane: door columns 2..4 on every wall, the tunnel's centre at 3. */
    public static final int LANE = 3;

    public final String id;
    /** Room box, shell to ceiling and wall to wall. */
    public final int sizeX, sizeY, sizeZ;
    /** Room box corner inside the cell. */
    public final int offX, offZ;
    public final Shape shape;
    public final int weight;
    private final MazeTheme[] themes;
    private final int[] themeWeights;

    RoomVariant(String id, int sizeX, int sizeY, int sizeZ, int offX, int offZ, Shape shape, int weight, Object... themes) {
        this.id = id;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.offX = offX;
        this.offZ = offZ;
        this.shape = shape;
        this.weight = weight;
        this.themes = new MazeTheme[themes.length / 2];
        this.themeWeights = new int[themes.length / 2];
        for (int i = 0; i < this.themes.length; i++) {
            this.themes[i] = (MazeTheme) themes[2 * i];
            this.themeWeights[i] = (Integer) themes[2 * i + 1];
        }
    }

    /** Clear air between floor and ceiling where nothing hangs down. */
    public int clearHeight() {
        return sizeY - 3;
    }

    /** Room-relative x, z of door column {@code w} (2..4, cell-relative) in the wall facing {@code dir}. */
    public int[] doorColumn(int dir, int w) {
        return switch (dir) {
            case ConnectedPlan.EAST -> new int[]{sizeX - 1, w - offZ};
            case ConnectedPlan.SOUTH -> new int[]{w - offX, sizeZ - 1};
            case ConnectedPlan.WEST -> new int[]{0, w - offZ};
            default -> new int[]{w - offX, 0};
        };
    }

    /** Pick for a fresh cell. Never {@link #CLASSIC}: that one only comes back from a map saved before variants. */
    public static RoomVariant forCell(long mazeSeed, int gx, int gz) {
        int total = 0;
        for (RoomVariant v : values()) total += v.weight;
        int roll = (int) Long.remainderUnsigned(MazeSeed.mix(mazeSeed ^ 0x7A21A27L, gx, gz), total);
        for (RoomVariant v : values()) {
            if (roll < v.weight) return v;
            roll -= v.weight;
        }
        return MEDIUM_OFFICE;
    }

    /** Theme of a room of this variant. {@code legacy} is the old per-cell theme, kept for {@link #CLASSIC}. */
    public MazeTheme theme(long mazeSeed, int gx, int gz, MazeTheme legacy) {
        if (themes.length == 0) return legacy;
        int total = 0;
        for (int w : themeWeights) total += w;
        int roll = (int) Long.remainderUnsigned(MazeSeed.mix(mazeSeed ^ 0x7E3E5L, gx, gz), total);
        for (int i = 0; i < themes.length; i++) {
            if (roll < themeWeights[i]) return themes[i];
            roll -= themeWeights[i];
        }
        return themes[0];
    }

    public static RoomVariant byId(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        for (RoomVariant v : values()) if (v.id.equals(key)) return v;
        return null;
    }

    /**
     * The room's roles: {@code inside[x][z]} columns are floor with air up to the ceiling, columns next to one (8 ways)
     * are wall, anything else is {@link BlockRole#OUTSIDE} (the cut corner of an L). Then the shape's overlay: pillars,
     * ceiling pipes, pool water. Doors and the feature block are put on by {@link ConnectedPlan#layout}.
     */
    BlockRole[][][] roles() {
        boolean[][] inside = new boolean[sizeX][sizeZ];
        for (int x = 1; x < sizeX - 1; x++) {
            for (int z = 1; z < sizeZ - 1; z++) {
                inside[x][z] = shape != Shape.L || x < sizeX / 2 + 1 || z < sizeZ / 2 + 1;
            }
        }
        BlockRole[][][] roles = new BlockRole[sizeX][sizeY][sizeZ];
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                boolean wall = false;
                if (!inside[x][z]) {
                    for (int dx = -1; dx <= 1 && !wall; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = x + dx, nz = z + dz;
                            if (nx >= 0 && nz >= 0 && nx < sizeX && nz < sizeZ && inside[nx][nz]) { wall = true; break; }
                        }
                    }
                }
                for (int y = 0; y < sizeY; y++) {
                    roles[x][y][z] = !inside[x][z] && !wall ? BlockRole.OUTSIDE
                            : y == 0 ? BlockRole.SHELL
                            : y == sizeY - 1 ? BlockRole.CEILING
                            : !inside[x][z] ? BlockRole.WALL
                            : y == 1 ? BlockRole.FLOOR
                            : BlockRole.AIR;
                }
            }
        }
        switch (shape) {
            case PILLARS -> {
                // a pillar every 4 columns, clear of the door lanes (cell-relative 1..5)
                for (int x = 6; x < sizeX - 2; x += 4) {
                    for (int z = 6; z < sizeZ - 2; z += 4) {
                        for (int y = 2; y < sizeY - 1; y++) roles[x][y][z] = BlockRole.WALL;
                    }
                }
            }
            case PIPES -> {
                // pipe runs under the ceiling along both long walls; 4 clear under them, more down the middle
                int y = sizeY - 2;
                for (int z = 1; z < sizeZ - 1; z++) {
                    roles[1][y][z] = BlockRole.WALL;
                    roles[sizeX - 2][y][z] = BlockRole.WALL;
                }
            }
            case POOL -> {
                // a pool in the far quarter, away from the door lanes, with a dry tile rim
                for (int x = 6; x < sizeX - 2; x++) {
                    for (int z = 6; z < sizeZ - 2; z++) roles[x][1][z] = BlockRole.WATER;
                }
            }
            default -> { }
        }
        return roles;
    }

    /** Container spots, room-relative and on the floor's air row: interior corners first, then mid-walls. */
    List<int[]> lootSpots() {
        List<int[]> out = new ArrayList<>();
        int lo = 1, hx = sizeX - 2, hz = sizeZ - 2;
        if (this == CLASSIC) {
            for (int[] s : MazePlanner.LOOT_SPOTS) out.add(s.clone());
            return out;
        }
        int[][] all = {{hx, 2, hz}, {lo, 2, hz}, {hx, 2, lo}, {lo, 2, lo}, {sizeX / 2, 2, hz}, {hx, 2, sizeZ / 2}};
        boolean narrow = sizeX <= 6 || sizeZ <= 6;
        for (int[] s : all) {
            // never in front of a doorway a later neighbour may open, unless the room is too small to keep any spot then
            if (!narrow && inFrontOfDoor(s[0], s[2])) continue;
            if (!out.isEmpty() && out.stream().anyMatch(o -> o[0] == s[0] && o[2] == s[2])) continue;
            out.add(s);
        }
        if (out.isEmpty()) out.add(new int[]{sizeX / 2, 2, sizeZ / 2});
        return out;
    }

    private boolean inFrontOfDoor(int rx, int rz) {
        int cx = rx + offX, cz = rz + offZ;
        boolean laneX = cx >= LANE - 1 && cx <= LANE + 1, laneZ = cz >= LANE - 1 && cz <= LANE + 1;
        return laneZ && (rx == 1 || rx == sizeX - 2) || laneX && (rz == 1 || rz == sizeZ - 2);
    }
}
