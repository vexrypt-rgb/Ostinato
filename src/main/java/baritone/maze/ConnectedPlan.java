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

import baritone.api.maze.MazeFurnishJob.Step;
import baritone.api.maze.MazeFurnishJob.StepKind;
import baritone.maze.BlockProbe.BlockKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.function.ToIntFunction;

/**
 * Ostinato: run107, {@code #maze.backrooms.connected}. Backrooms rooms on a {@link #PITCH} grid, joined into one
 * walkable complex by the tunnels of {@code #maze.tunnels}: 3 wide, 3 high, stair ramps that climb 1 per column with
 * jump headroom where the next column steps up. A room has a 3x3 doorway in each wall that leads to a tunnel; there is
 * no corridor stub. Each new room is joined to one built neighbour, so the complex is one connected tree.
 * <p>
 * Cells are packed like Minecraft's {@code BlockPos.asLong} ({@link #key}). Pure: the world comes in as a
 * {@link BlockProbe}.
 */
public final class ConnectedPlan {

    /** Blocks between room origins: a 7 wide room and a 9 long tunnel. */
    public static final int PITCH = 16;
    /** Room box edge on every axis (shell to ceiling, wall to wall). */
    public static final int ROOM = 7;
    /** Floor difference one tunnel may climb: 10 columns door to door, 1 per column, a landing at each end. */
    public static final int MAX_RISE = 8;

    /** Ostinato: absolute floor Y from setting mazeDepthY, or MIN_VALUE for 16 under the ground. Set by MazeProcess. */
    public static volatile int depthOverride = Integer.MIN_VALUE;
    /**
     * Ostinato: run112. How many grid steps past a room the ring search will still place the next one. A blocked
     * neighbour is crossed by a longer corridor instead of ending the expand.
     */
    public static final int MAX_SPAN = 4;
    /** Air pocket larger than this is a cave, not a prior room. Biggest room interior is well under it. */
    private static final int POCKET_CAP = 2500;
    /** Ostinato: 2026-11-02. Tallest room box of any {@link RoomVariant}, for the world-height clamps. */
    public static final int MAX_HEIGHT = 9;
    public static final int EAST = 0, SOUTH = 1, WEST = 2, NORTH = 3;
    static final int[][] DIR = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Keeps connected themes and loot rolls apart from the backrooms grid of the same maze seed. */
    private static final long SALT = 0xC0E7EC7EDL;

    private ConnectedPlan() {}

    // ---- cells ----------------------------------------------------------------------------------------------------

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int x(long k) {
        return (int) (k >> 38);
    }

    public static int y(long k) {
        return (int) (k << 52 >> 52);
    }

    public static int z(long k) {
        return (int) (k << 26 >> 38);
    }

    static long offset(long k, int dx, int dy, int dz) {
        return key(x(k) + dx, y(k) + dy, z(k) + dz);
    }

    public static int opposite(int dir) {
        return (dir + 2) & 3;
    }

    public static int dx(int dir) {
        return DIR[dir][0];
    }

    public static int dz(int dir) {
        return DIR[dir][1];
    }

    public static long roomSeed(long mazeSeed, int gx, int gz) {
        return MazeSeed.mix(mazeSeed ^ SALT, gx, gz);
    }

    public static MazeTheme theme(long mazeSeed, int gx, int gz) {
        return MazeTheme.forCell(mazeSeed ^ SALT, gx, gz);
    }

    /** Ostinato: 2026-11-02. The variant a fresh room in this cell is built as. Pure in seed and cell. */
    public static RoomVariant variant(long mazeSeed, int gx, int gz) {
        return RoomVariant.forCell(mazeSeed ^ SALT, gx, gz);
    }

    /** Ostinato: 2026-11-02. A room's theme: its variant's pick, or the old per-cell theme for a {@link RoomVariant#CLASSIC}. */
    public static MazeTheme theme(long mazeSeed, Room r) {
        return r.variant.theme(mazeSeed ^ SALT, r.gx, r.gz, theme(mazeSeed, r.gx, r.gz));
    }

    // ---- rooms ----------------------------------------------------------------------------------------------------

    /**
     * One room. {@code doors} has bit {@code 1 << dir} for every wall that opens onto a tunnel. Ostinato: 2026-11-02,
     * {@code variant} is its shape and size; a room without one (an older map) is {@link RoomVariant#CLASSIC}.
     */
    public record Room(int gx, int gz, int originY, int doors, RoomVariant variant) {
        public Room {
            if (variant == null) variant = RoomVariant.CLASSIC;
        }

        public Room(int gx, int gz, int originY, int doors) {
            this(gx, gz, originY, doors, RoomVariant.CLASSIC);
        }

        public boolean door(int dir) {
            return (doors & (1 << dir)) != 0;
        }

        public Room withDoor(int dir) {
            return new Room(gx, gz, originY, doors | (1 << dir), variant);
        }
    }

    /**
     * The backrooms room box of {@link MazePlanner#layout} without the corridor stub: shell, floor, 4 high interior,
     * walls, ceiling, the feature block and the loot spots. No torch role: {@link ConnectedLight} lights the complex as a
     * whole. A door is 3 wide and 3 high in the middle of its wall; the wall cells under it become floor.
     */
    public static RoomPlan layout(long mazeSeed, int gx, int gz, int originX, int originY, int originZ, int doors) {
        return layout(mazeSeed, new Room(gx, gz, originY, doors), originX, originZ);
    }

    /**
     * Ostinato: 2026-11-02. A room of any {@link RoomVariant}: its box at {@code cellX + offX, cellZ + offZ}, a 3x3 door
     * on the door lane of every open wall, and the feature block on one of the variant's loot spots. A
     * {@link RoomVariant#CLASSIC} room comes out cell for cell as the fixed 7x7x7 layout did.
     */
    public static RoomPlan layout(long mazeSeed, Room r, int cellX, int cellZ) {
        RoomVariant v = r.variant();
        long roomSeed = roomSeed(mazeSeed, r.gx(), r.gz());
        BlockRole[][][] roles = v.roles();
        for (int dir = 0; dir < 4; dir++) {
            if (!r.door(dir)) continue;
            for (int w = 2; w <= 4; w++) {
                int[] at = v.doorColumn(dir, w);
                roles[at[0]][1][at[1]] = BlockRole.FLOOR;
                for (int y = 2; y <= 4; y++) roles[at[0]][y][at[1]] = BlockRole.AIR;
            }
        }
        List<int[]> spots = v.lootSpots();
        int[] feature = spots.get((int) Long.remainderUnsigned(roomSeed, spots.size()));
        if (roles[feature[0]][feature[1]][feature[2]] == BlockRole.AIR) roles[feature[0]][feature[1]][feature[2]] = BlockRole.FEATURE;
        return new RoomPlan(r.gx(), r.gz(), mazeSeed, roomSeed, theme(mazeSeed, r), cellX + v.offX, r.originY(), cellZ + v.offZ, roles, v);
    }

    /** The fixed-box body {@link #layout} had before variants, kept for the CLASSIC equivalence test. */
    static RoomPlan legacyLayout(long mazeSeed, int gx, int gz, int originX, int originY, int originZ, int doors) {
        long roomSeed = roomSeed(mazeSeed, gx, gz);
        BlockRole[][][] roles = new BlockRole[ROOM][ROOM][ROOM];
        for (int x = 0; x < ROOM; x++) {
            for (int y = 0; y < ROOM; y++) {
                for (int z = 0; z < ROOM; z++) {
                    boolean edge = x == 0 || x == ROOM - 1 || z == 0 || z == ROOM - 1;
                    roles[x][y][z] = y == 0 ? BlockRole.SHELL
                            : y == ROOM - 1 ? BlockRole.CEILING
                            : edge ? BlockRole.WALL
                            : y == 1 ? BlockRole.FLOOR
                            : BlockRole.AIR;
                }
            }
        }
        for (int dir = 0; dir < 4; dir++) {
            if ((doors & (1 << dir)) == 0) continue;
            for (int w = 2; w <= 4; w++) {
                int[] at = doorColumn(dir, w);
                roles[at[0]][1][at[1]] = BlockRole.FLOOR;
                for (int y = 2; y <= 4; y++) roles[at[0]][y][at[1]] = BlockRole.AIR;
            }
        }
        int[] feature = MazePlanner.LOOT_SPOTS[(int) Long.remainderUnsigned(roomSeed, MazePlanner.LOOT_SPOTS.length)];
        roles[feature[0]][feature[1]][feature[2]] = BlockRole.FEATURE;
        return new RoomPlan(gx, gz, mazeSeed, roomSeed, theme(mazeSeed, gx, gz), originX, originY, originZ, roles);
    }

    /** Room-relative x, z of door column {@code w} (2..4) in the wall facing {@code dir}. */
    static int[] doorColumn(int dir, int w) {
        return switch (dir) {
            case EAST -> new int[]{ROOM - 1, w};
            case SOUTH -> new int[]{w, ROOM - 1};
            case WEST -> new int[]{0, w};
            default -> new int[]{w, 0};
        };
    }

    static void addAir(RoomPlan plan, Collection<Long> out) {
        for (int x = 0; x < plan.sizeX; x++) {
            for (int y = 0; y < plan.sizeY; y++) {
                for (int z = 0; z < plan.sizeZ; z++) {
                    if (plan.role(x, y, z) == BlockRole.AIR) out.add(key(plan.originX + x, plan.originY + y, plan.originZ + z));
                }
            }
        }
    }

    // ---- the complex ----------------------------------------------------------------------------------------------

    /** Every room built so far, in build order, on a grid anchored at {@code anchorX, anchorZ} (room 0,0's corner). */
    public static final class Complex {
        public final long seed;
        public final int anchorX;
        public final int anchorZ;
        private final LinkedHashMap<Long, Room> rooms = new LinkedHashMap<>();
        private final Set<Long> failed = new HashSet<>();

        public Complex(long seed, int anchorX, int anchorZ) {
            this.seed = seed;
            this.anchorX = anchorX;
            this.anchorZ = anchorZ;
        }

        public Complex copy() {
            Complex c = new Complex(seed, anchorX, anchorZ);
            c.rooms.putAll(rooms);
            c.failed.addAll(failed);
            return c;
        }

        static long cell(int gx, int gz) {
            return ((long) gx << 32) ^ (gz & 0xffffffffL);
        }

        public Room room(int gx, int gz) {
            return rooms.get(cell(gx, gz));
        }

        public List<Room> rooms() {
            return List.copyOf(rooms.values());
        }

        public boolean isEmpty() {
            return rooms.isEmpty();
        }

        /** Adds a room or replaces the one in its cell (a new door), keeping its place in the build order. */
        public void put(Room r) {
            rooms.put(cell(r.gx, r.gz), r);
        }

        public void fail(int gx, int gz) {
            failed.add(cell(gx, gz));
        }

        public boolean failed(int gx, int gz) {
            return failed.contains(cell(gx, gz));
        }

        public Set<Long> failedCells() {
            return Set.copyOf(failed);
        }

        /**
         * The room {@code r}'s door in {@code dir} opens onto: the next room that way, up to {@link #MAX_SPAN} cells,
         * that opens back. A room in between that does not face this way breaks the link.
         */
        public Room linked(Room r, int dir) {
            if (r == null || !r.door(dir)) return null;
            for (int s = 1; s <= MAX_SPAN; s++) {
                Room n = room(r.gx + DIR[dir][0] * s, r.gz + DIR[dir][1] * s);
                if (n == null) continue;
                return n.door(opposite(dir)) ? n : null;
            }
            return null;
        }

        /** True when a corridor already runs through this grid cell, so it is not a place for another room. */
        public boolean tunnelCrosses(int gx, int gz) {
            for (Room r : rooms.values()) {
                for (int d = EAST; d <= SOUTH; d++) {
                    Room n = linked(r, d);
                    if (n == null) continue;
                    int steps = d == EAST ? n.gx - r.gx : n.gz - r.gz;
                    for (int s = 1; s < steps; s++) {
                        if (r.gx + DIR[d][0] * s == gx && r.gz + DIR[d][1] * s == gz) return true;
                    }
                }
            }
            return false;
        }

        public int originX(int gx) {
            return anchorX + gx * PITCH;
        }

        public int originZ(int gz) {
            return anchorZ + gz * PITCH;
        }

        public RoomPlan plan(Room r) {
            return layout(seed, r, originX(r.gx), originZ(r.gz));
        }

        /** Room box corner in the world (Ostinato: 2026-11-02, the variant's offset inside its cell). */
        public int boxX(Room r) {
            return originX(r.gx) + r.variant.offX;
        }

        public int boxZ(Room r) {
            return originZ(r.gz) + r.variant.offZ;
        }

        /** The room whose box holds this cell, or null. */
        public Room roomAt(int x, int y, int z) {
            int gx = Math.floorDiv(x - anchorX, PITCH), gz = Math.floorDiv(z - anchorZ, PITCH);
            Room r = room(gx, gz);
            if (r == null) return null;
            RoomVariant v = r.variant;
            int rx = x - boxX(r), rz = z - boxZ(r), ry = y - r.originY;
            return rx >= 0 && rz >= 0 && rx < v.sizeX && rz < v.sizeZ && ry >= 0 && ry < v.sizeY ? r : null;
        }

        /** Role of a cell inside some room box, or null outside every room. */
        public BlockRole roleAt(long k) {
            int x = x(k), y = y(k), z = z(k);
            Room r = roomAt(x, y, z);
            if (r == null) return null;
            return plan(r).role(x - boxX(r), y - r.originY, z - boxZ(r));
        }

        /** Ostinato: 2026-11-02. A room cell that is part of the build, not the cut corner of an L. */
        boolean inBuild(long k) {
            BlockRole r = roleAt(k);
            return r != null && r != BlockRole.OUTSIDE;
        }

        /**
         * Air of the tunnel from {@code a} to its neighbour {@code b} toward {@code dir} (EAST or SOUTH), the door
         * columns excluded (they belong to the rooms). Same profile as {@link TunnelPlan#carve}: from the low end the
         * feet stay level for one column, then climb 1 per column; a column is 3 high, plus a row where the next column
         * steps up.
         */
        public Set<Long> tunnelAir(Room a, Room b, int dir) {
            Set<Long> out = new LinkedHashSet<>();
            // Ostinato: 2026-11-02. Door wall to door wall of the two variants; the lane is the cells' shared column 3.
            int wallA = dir == EAST ? boxX(a) + a.variant.sizeX - 1 : boxZ(a) + a.variant.sizeZ - 1;
            int span = Math.max(1, dir == EAST ? b.gx - a.gx : b.gz - a.gz); // Ostinato: run112, rooms more than one cell apart
            int run = runBetween(a.variant, b.variant, dir, span); // door column of a (t = 0) to door column of b (t = run)
            int feetA = a.originY + 2, feetB = b.originY + 2;
            int rise = feetB - feetA, up = Math.abs(rise), lo = Math.min(feetA, feetB);
            int ax = originX(a.gx), az = originZ(a.gz);
            for (int t = 1; t < run; t++) {
                int s = rise >= 0 ? t : run - t;
                int fy = lo + Math.max(0, Math.min(s - 1, up));
                int next = lo + Math.max(0, Math.min(s, up));
                int top = next > fy ? fy + 3 : fy + 2;
                for (int w = -1; w <= 1; w++) {
                    for (int y = fy; y <= top; y++) {
                        out.add(dir == EAST ? key(wallA + t, y, az + RoomVariant.LANE + w) : key(ax + RoomVariant.LANE + w, y, wallA + t));
                    }
                }
            }
            return out;
        }

        /** Every tunnel, keyed by the room it starts from (its west or north end) and direction. */
        public List<Set<Long>> tunnels() {
            List<Set<Long>> out = new ArrayList<>();
            for (Room r : rooms.values()) {
                for (int d = EAST; d <= SOUTH; d++) {
                    if (!r.door(d)) continue;
                    Room n = linked(r, d);
                    if (n != null) out.add(tunnelAir(r, n, d));
                }
            }
            return out;
        }

        /** Every carved cell: room interiors, doorways and tunnels. */
        public Set<Long> air() {
            Set<Long> out = new HashSet<>();
            for (Room r : rooms.values()) addAir(plan(r), out);
            for (Set<Long> t : tunnels()) out.addAll(t);
            return out;
        }

        /** Cells the planned containers fill (all rooms). They stop light and are not spawn cells. */
        public Set<Long> containers() {
            Set<Long> out = new HashSet<>();
            for (Room r : rooms.values()) {
                for (LootPlanner.LootPlacement p : LootPlanner.plan(plan(r), LootPlanner.PlacedLedger.EMPTY)) {
                    out.add(key(p.x(), p.y(), p.z()));
                }
            }
            return out;
        }
    }

    /** Ostinato: 2026-11-02. Columns from {@code a}'s door wall to {@code b}'s, {@code b} one cell toward {@code dir} (EAST or SOUTH). */
    static int runBetween(RoomVariant a, RoomVariant b, int dir) {
        return runBetween(a, b, dir, 1);
    }

    /** Same, when {@code b} sits {@code span} cells toward {@code dir}. Span 1 is the adjacent tunnel. */
    static int runBetween(RoomVariant a, RoomVariant b, int dir, int span) {
        int pitch = PITCH * Math.max(1, span);
        return dir == EAST ? pitch + b.offX - a.offX - a.sizeX + 1 : pitch + b.offZ - a.offZ - a.sizeZ + 1;
    }

    /** Door-to-door columns, swapping the pair so {@code dir} is read as EAST or SOUTH. */
    static int runFor(RoomVariant parent, RoomVariant child, int dir, int span) {
        return dir == WEST || dir == NORTH ? runBetween(child, parent, opposite(dir), span) : runBetween(parent, child, dir, span);
    }

    /**
     * Ostinato: 2026-11-02. Floor difference the tunnel between two variants can climb: one per column with a landing at
     * each end, so two big rooms side by side (a short tunnel) stay nearly level. {@code dir} points parent to child.
     * Span 1 stays inside {@link #MAX_RISE}. A longer corridor (run112) may climb the whole stair run, past that cap.
     */
    public static int maxRise(RoomVariant parent, RoomVariant child, int dir) {
        return maxRise(parent, child, dir, 1);
    }

    public static int maxRise(RoomVariant parent, RoomVariant child, int dir, int span) {
        int climb = Math.max(0, runFor(parent, child, dir, span) - 2);
        return span <= 1 ? Math.min(MAX_RISE, climb) : climb;
    }

    /** Stair limit with the cap off, so a short adjacent tunnel can still step a little past {@link #MAX_RISE}. */
    static int stretchRise(RoomVariant parent, RoomVariant child, int dir, int span) {
        return Math.max(0, runFor(parent, child, dir, Math.max(1, span)) - 2);
    }

    // ---- one new room and its tunnel ------------------------------------------------------------------------------

    /**
     * Ostinato: per-tunnel cladding, best first, each list ending in what the dig itself drops. A palette other than
     * plain cobble or cobbled deepslate needs its {@code source} stone (polished blocks are crafted 1:1 from it), so it
     * is only picked where that stone is plentiful around the tunnel: the bot mines it next door, never goes hunting.
     */
    public enum TunnelPalette {
        COBBLE("cobble", null, List.of("cobblestone"), List.of("cobblestone"), List.of("cobblestone")),
        ANDESITE("andesite", "andesite", List.of("polished_andesite", "andesite", "cobblestone"), List.of("andesite", "cobblestone"),
                List.of("polished_andesite", "andesite", "cobblestone")),
        DIORITE("diorite", "diorite", List.of("polished_diorite", "diorite", "cobblestone"), List.of("diorite", "cobblestone"),
                List.of("diorite", "cobblestone")),
        GRANITE("granite", "granite", List.of("polished_granite", "granite", "cobblestone"), List.of("granite", "cobblestone"),
                List.of("granite", "cobblestone")),
        QUARRY("quarry", "andesite", List.of("andesite", "cobblestone"), List.of("cobblestone"), List.of("andesite", "cobblestone")),
        DEEPSLATE("deepslate", null, List.of("polished_deepslate", "cobbled_deepslate", "cobblestone"),
                List.of("cobbled_deepslate", "cobblestone"), List.of("cobbled_deepslate", "cobblestone")),
        // Ostinato: tuff family. Tuff -> polished_tuff (2x2) -> tuff_bricks (2x2), crafted by TenorClef from tuff mined nearby.
        TUFF("tuff", "tuff", List.of("polished_tuff", "tuff", "cobbled_deepslate", "cobblestone"),
                List.of("tuff_bricks", "polished_tuff", "tuff", "cobbled_deepslate", "cobblestone"),
                List.of("polished_tuff", "tuff_bricks", "cobbled_deepslate", "cobblestone"));

        public final String id;
        /** Stone that must be near the tunnel, or null when the dig itself yields the palette. */
        public final String source;
        private final List<String> floor, wall, ceiling;

        TunnelPalette(String id, String source, List<String> floor, List<String> wall, List<String> ceiling) {
            this.id = id;
            this.source = source;
            this.floor = floor;
            this.wall = wall;
            this.ceiling = ceiling;
        }

        public List<String> ids(BlockRole role) {
            return role == BlockRole.FLOOR ? floor : role == BlockRole.CEILING ? ceiling : wall;
        }

        /**
         * Stable per tunnel and per world. Deepslate only where the floor is in the deepslate band, as with room cladding.
         * {@code nearby} counts a block id around the tunnel (null: counts nothing); a palette whose source count is
         * under {@code need} falls through to the next in roll order and finally to the plain one.
         */
        public static TunnelPalette pick(long mazeSeed, int gx, int gz, int dir, int lowestFloorY, ToIntFunction<String> nearby, int need) {
            int roll = (int) Long.remainderUnsigned(MazeSeed.mix(mazeSeed ^ SALT ^ 0x7A11E7L, gx * 2 + dir, gz), 100);
            List<TunnelPalette> order = new ArrayList<>();
            if (MazeFurnishPlanner.deepslateLayer(lowestFloorY)) {
                if (roll >= 50) order.add(TUFF); // Ostinato: half of deep tunnels try tuff bricks when tuff is near
                order.add(DEEPSLATE);
            } else {
                TunnelPalette first = roll < 35 ? COBBLE : roll < 55 ? ANDESITE : roll < 70 ? DIORITE : roll < 85 ? GRANITE : QUARRY;
                List<TunnelPalette> stones = List.of(ANDESITE, DIORITE, GRANITE, QUARRY);
                order.add(first);
                if (first != COBBLE) {
                    int at = stones.indexOf(first);
                    for (int i = 1; i < stones.size(); i++) order.add(stones.get((at + i) % stones.size()));
                    order.add(COBBLE);
                }
            }
            for (TunnelPalette p : order) {
                if (p.source == null || nearby != null && nearby.applyAsInt(p.source) >= need) return p;
            }
            return order.get(order.size() - 1);
        }
    }

    /** A room to add, the parent it is joined to (null for the first room) and the tunnel between them. */
    public static final class Segment {
        public final Complex before;
        public final Complex after;
        public final Room room;
        /** The parent with its new door, or null. */
        public final Room parent;
        /** Direction from the parent to the new room, or -1. */
        public final int dir;
        public final RoomPlan plan;
        /** Cells this segment carves: the room, the tunnel and the parent's new doorway. */
        public final Set<Long> newAir;
        /** Every cell carved before this segment. */
        public final Set<Long> oldAir;
        public final Set<Long> tunnel;
        /** Floor, wall and ceiling cells of the tunnel that lie outside both room boxes. */
        public final Set<Long> ring;
        /** Lowest tunnel floor block, for the deepslate band. */
        public final int lowestFloorY;
        /** Plain until {@link #choosePalette} has looked at the stone around the tunnel. */
        public TunnelPalette palette;

        Segment(Complex before, Set<Long> oldAir, Room parentBefore, int dir, int gx, int gz, int originY) {
            this.before = before;
            this.oldAir = oldAir;
            this.dir = parentBefore == null ? -1 : dir;
            this.room = new Room(gx, gz, originY, parentBefore == null ? 0 : 1 << opposite(dir), variant(before.seed, gx, gz));
            this.parent = parentBefore == null ? null : parentBefore.withDoor(dir);
            this.after = before.copy();
            after.put(room);
            if (parent != null) after.put(parent);
            this.plan = after.plan(room);
            Set<Long> air = new LinkedHashSet<>();
            addAir(plan, air);
            Set<Long> tun = new LinkedHashSet<>();
            if (parent != null) {
                tun = dir == EAST || dir == SOUTH ? after.tunnelAir(parent, room, dir) : after.tunnelAir(room, parent, opposite(dir));
                air.addAll(tun);
                Set<Long> parentAir = new HashSet<>();
                addAir(after.plan(parent), parentAir);
                for (long k : parentAir) if (!oldAir.contains(k)) air.add(k); // the new doorway in the parent's wall
            }
            this.newAir = air;
            this.tunnel = tun;
            Set<Long> ring = new LinkedHashSet<>();
            int lowest = Integer.MAX_VALUE;
            for (long k : tun) {
                lowest = Math.min(lowest, y(k) - 1);
                for (int[] f : FACES) {
                    long n = offset(k, f[0], f[1], f[2]);
                    if (!isAir(n) && !after.inBuild(n)) ring.add(n);
                }
            }
            this.ring = ring;
            this.lowestFloorY = lowest == Integer.MAX_VALUE ? originY + 1 : lowest;
            choosePalette(null);
        }

        /** Picks the tunnel's palette; {@code nearby} counts a stone id around the tunnel (see {@link TunnelPalette#pick}). */
        public TunnelPalette choosePalette(ToIntFunction<String> nearby) {
            int pgx = parent == null ? room.gx : Math.min(room.gx, parent.gx), pgz = parent == null ? room.gz : Math.min(room.gz, parent.gz);
            palette = TunnelPalette.pick(before.seed, pgx, pgz, dir == WEST || dir == EAST ? EAST : SOUTH, lowestFloorY, nearby,
                    2 * ring.size());
            return palette;
        }

        /**
         * Cells the dig must not break on its way (path shortcuts): every solid cell around the older complex, and its
         * torches and containers. The new segment's own cells are not guarded; the first room has nothing to guard.
         */
        public Set<Long> guard() {
            Set<Long> out = new HashSet<>(oldAir);
            for (long k : oldAir) {
                for (int[] f : FACES) {
                    long n = offset(k, f[0], f[1], f[2]);
                    if (!oldAir.contains(n)) out.add(n);
                }
            }
            out.removeAll(newAir);
            return out;
        }

        /** World bounds of the tunnel, or of the room when there is no tunnel: {x0, y0, z0, x1, y1, z1}. */
        public int[] tunnelBox() {
            Collection<Long> cells = tunnel.isEmpty() ? newAir : tunnel;
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (long k : cells) {
                b[0] = Math.min(b[0], x(k));
                b[1] = Math.min(b[1], y(k));
                b[2] = Math.min(b[2], z(k));
                b[3] = Math.max(b[3], x(k));
                b[4] = Math.max(b[4], y(k));
                b[5] = Math.max(b[5], z(k));
            }
            return b;
        }

        public boolean isAir(long k) {
            return newAir.contains(k) || oldAir.contains(k);
        }

        /** The new room's solid cells and the tunnel ring: what has to stand between the complex and the world. */
        public Set<Long> shell() {
            Set<Long> out = new LinkedHashSet<>();
            for (int x = 0; x < plan.sizeX; x++) {
                for (int y = 0; y < plan.sizeY; y++) {
                    for (int z = 0; z < plan.sizeZ; z++) {
                        if (plan.role(x, y, z).solid()) out.add(key(plan.originX + x, plan.originY + y, plan.originZ + z));
                    }
                }
            }
            out.addAll(ring);
            return out;
        }

        boolean planned(long k) {
            return isAir(k) || ring.contains(k) || after.inBuild(k); // Ostinato: an L's cut corner is world, and is checked
        }

        /** True for a shell cell with carved air right under it. */
        boolean ceiling(long k) {
            BlockRole r = after.roleAt(k);
            return r != null && r != BlockRole.OUTSIDE ? r == BlockRole.CEILING : isAir(offset(k, 0, -1, 0));
        }
    }

    public static Segment first(Complex before, int gx, int gz, int originY) {
        return new Segment(before, before.air(), null, -1, gx, gz, originY);
    }

    public static Segment join(Complex before, Set<Long> oldAir, Room parent, int dir, int originY) {
        return join(before, oldAir, parent, dir, originY, 1);
    }

    /** Joins {@code parent} to the room {@code span} cells toward {@code dir}. Span 1 is the neighbour. */
    public static Segment join(Complex before, Set<Long> oldAir, Room parent, int dir, int originY, int span) {
        int s = Math.max(1, span);
        return new Segment(before, oldAir, parent, dir, parent.gx + DIR[dir][0] * s, parent.gz + DIR[dir][1] * s, originY);
    }

    /**
     * What keeps a segment from being sealed and hidden, the way {@link ShellSeal} judges a backrooms room: fluid or
     * unloaded blocks in or around it, open air or caves touching its shell, and less than {@link MazeSeed#COVER} solid
     * blocks over any ceiling. Before the dig this decides whether the segment can be built; after it, every cell
     * returned is a seal target (the bot's own shafts). Shell cells that are not solid are not listed here: cladding
     * places them, and {@link #shellHoles} lists the rest.
     */
    public static List<Long> breaches(Segment s, BlockProbe probe, boolean dug) {
        Set<Long> out = new LinkedHashSet<>();
        if (!dug) {
            for (long k : s.newAir) {
                BlockKind kind = probe.get(x(k), y(k), z(k));
                if (kind == BlockKind.FLUID || kind == BlockKind.UNKNOWN) out.add(k);
            }
        }
        for (long k : s.shell()) {
            BlockKind self = probe.get(x(k), y(k), z(k));
            if (!dug && (self == BlockKind.FLUID || self == BlockKind.UNKNOWN)) out.add(k);
            for (int[] f : FACES) {
                long n = offset(k, f[0], f[1], f[2]);
                if (s.planned(n)) continue;
                if (probe.get(x(n), y(n), z(n)) != BlockKind.SOLID) out.add(n);
            }
            if (s.ceiling(k)) {
                for (int i = 1; i <= MazeSeed.COVER; i++) {
                    long m = offset(k, 0, i, 0);
                    if (s.planned(m)) continue;
                    if (probe.get(x(m), y(m), z(m)) != BlockKind.SOLID) out.add(m);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Planned solid cells of the new room that are not solid and that no cladding step places (shell, corners). */
    public static List<Long> shellHoles(Segment s, BlockProbe probe, Set<Long> clad) {
        List<Long> out = new ArrayList<>();
        for (long k : s.shell()) {
            if (clad.contains(k) || s.ring.contains(k)) continue;
            BlockRole r = s.after.roleAt(k);
            if (r == BlockRole.FEATURE) continue;
            if (probe.get(x(k), y(k), z(k)) != BlockKind.SOLID) out.add(k);
        }
        return out;
    }

    /**
     * Cladding for a segment, as LIGHT-kind steps like room cladding: the new room by its theme, the parent's new door
     * floor by the parent's theme, and every tunnel face by the tunnel palette (floor under air, ceiling over air, the
     * rest wall). Cells that already faced older air were clad with an earlier room and are not listed again.
     */
    public static List<Step> cladding(Segment s) {
        List<Step> steps = new ArrayList<>();
        MazeFurnishPlanner.addCladding(s.plan, steps);
        RoomPlan parentPlan = s.parent == null ? null : s.after.plan(s.parent);
        if (parentPlan != null && parentPlan.theme != s.plan.theme) {
            // Ostinato: 2026-11-02, soft edges. Near the door to the parent, the parent's palette bleeds in.
            for (int i = 0; i < steps.size(); i++) {
                Step st = steps.get(i);
                int pct = blendPct(s.plan, opposite(s.dir), st.x(), st.z());
                if (pct > 0 && blendRoll(s.before.seed, st.x(), st.y(), st.z()) < pct) {
                    BlockRole role = s.plan.role(st.x() - s.plan.originX, st.y() - s.plan.originY, st.z() - s.plan.originZ);
                    steps.set(i, new Step(st.kind(), st.x(), st.y(), st.z(),
                            blendIds(MazeFurnishPlanner.cladIds(parentPlan.theme, role, parentPlan.originY), st.blockIds()), st.contents()));
                }
            }
        }
        if (s.parent != null) {
            RoomPlan pp = s.after.plan(s.parent);
            for (int x = 0; x < pp.sizeX; x++) {
                for (int y = 0; y < pp.sizeY; y++) {
                    for (int z = 0; z < pp.sizeZ; z++) {
                        BlockRole role = pp.role(x, y, z);
                        if (role != BlockRole.FLOOR && role != BlockRole.WALL && role != BlockRole.CEILING) continue;
                        long k = key(pp.originX + x, pp.originY + y, pp.originZ + z);
                        boolean touchesNew = false, touchesOld = false;
                        for (int[] f : FACES) {
                            long n = offset(k, f[0], f[1], f[2]);
                            if (s.oldAir.contains(n)) touchesOld = true;
                            else if (s.newAir.contains(n)) touchesNew = true;
                        }
                        if (touchesNew && !touchesOld) {
                            List<String> ids = MazeFurnishPlanner.cladIds(pp.theme, role, pp.originY);
                            if (pp.theme != s.plan.theme && blendRoll(s.before.seed, x(k), y(k), z(k)) < 50) {
                                ids = blendIds(MazeFurnishPlanner.cladIds(s.plan.theme, role, s.plan.originY), ids); // Ostinato: soft edge
                            }
                            steps.add(new Step(StepKind.LIGHT, x(k), y(k), z(k), ids, List.of()));
                        }
                    }
                }
            }
        }
        List<Long> ring = new ArrayList<>(s.ring);
        ring.sort(Comparator.comparingInt(ConnectedPlan::y).thenComparingInt(ConnectedPlan::x).thenComparingInt(ConnectedPlan::z));
        for (long k : ring) {
            BlockRole role = s.isAir(offset(k, 0, 1, 0)) ? BlockRole.FLOOR : s.isAir(offset(k, 0, -1, 0)) ? BlockRole.CEILING : BlockRole.WALL;
            steps.add(new Step(StepKind.LIGHT, x(k), y(k), z(k), s.palette.ids(role), List.of()));
        }
        return steps;
    }

    /**
     * Ostinato: 2026-11-02. Chance (0..100) that a clad cell of {@code plan} takes the neighbour's palette: 60 in the
     * wall with the door toward {@code dir}, 30 one column in, 15 two in, and only within 3 of the door lane.
     */
    static int blendPct(RoomPlan plan, int dir, int wx, int wz) {
        RoomVariant v = plan.variant;
        int rx = wx - plan.originX, rz = wz - plan.originZ;
        int depth = switch (dir) {
            case EAST -> plan.sizeX - 1 - rx;
            case WEST -> rx;
            case SOUTH -> plan.sizeZ - 1 - rz;
            default -> rz;
        };
        int lateral = dir == EAST || dir == WEST ? rz + v.offZ : rx + v.offX; // cell-relative, like the lane
        if (Math.abs(lateral - RoomVariant.LANE) > 3) return 0;
        return depth == 0 ? 60 : depth == 1 ? 30 : depth == 2 ? 15 : 0;
    }

    static int blendRoll(long seed, int x, int y, int z) {
        return (int) Long.remainderUnsigned(MazeSeed.mix(seed ^ SALT ^ 0xB1E4DL, x * 31 + y, z), 100);
    }

    /** The neighbour's ids first, then this room's own as the fallback the furnisher can always reach. */
    static List<String> blendIds(List<String> neighbour, List<String> own) {
        LinkedHashSet<String> out = new LinkedHashSet<>(neighbour);
        out.addAll(own);
        return List.copyOf(out);
    }

    // ---- the hidden way in ----------------------------------------------------------------------------------------

    /**
     * Ostinato: run107. A shaft for entering a room that is still solid rock: a 1x1 column dug from the ground block
     * {@code groundY} down through the room's ceiling, every cell refilled above the bot's head as it sinks, so the
     * ground closes behind it. Clear when the whole column is solid (no cave to drop into, so no cell is skipped), and
     * nothing beside it, or on top of it, is fluid or unloaded.
     */
    public static boolean shaftClear(BlockProbe probe, int x, int z, int groundY, int ceilingY) {
        if (groundY < ceilingY) return false;
        for (int y = groundY; y >= ceilingY; y--) {
            if (probe.get(x, y, z) != BlockKind.SOLID) return false;
            for (int[] d : DIR) {
                BlockKind side = probe.get(x + d[0], y, z + d[1]);
                if (side == BlockKind.FLUID || side == BlockKind.UNKNOWN) return false;
            }
        }
        BlockKind top = probe.get(x, groundY + 1, z);
        return top != BlockKind.FLUID && top != BlockKind.UNKNOWN;
    }

    /** Interior columns of a room, nearest to {@code x, z} first: where an entry shaft may come down. */
    public static List<int[]> entryColumns(Complex c, Room r, int x, int z) {
        List<int[]> out = new ArrayList<>();
        RoomPlan plan = c.plan(r);
        for (int rx = 1; rx < plan.sizeX - 1; rx++) {
            for (int rz = 1; rz < plan.sizeZ - 1; rz++) {
                // Ostinato: 2026-11-02. Only real floor: not into a pool, an L's cut corner or under a pillar.
                if (plan.role(rx, 1, rz) == BlockRole.FLOOR && plan.role(rx, 2, rz) == BlockRole.AIR) {
                    out.add(new int[]{plan.originX + rx, plan.originZ + rz});
                }
            }
        }
        out.sort(Comparator.comparingInt((int[] p) -> (p[0] - x) * (p[0] - x) + (p[1] - z) * (p[1] - z))
                .thenComparingInt(p -> p[0]).thenComparingInt(p -> p[1]));
        return out;
    }

    // ---- torches -------------------------------------------------------------------------------------------------

    /** A standing torch needs a sturdy top under it: not open air, not a container, not the feature block. */
    public static boolean mountable(Complex c, Set<Long> open, Set<Long> containers, long k) {
        long below = offset(k, 0, -1, 0);
        BlockRole role = c.roleAt(below);
        return !open.contains(below) && !containers.contains(below) && role != BlockRole.FEATURE && role != BlockRole.WATER;
    }

    /** Where a torch looks placed on purpose: the middle of a room's floor, or the centre lane of a tunnel. */
    public static boolean preferredTorch(Complex c, long k) {
        int x = x(k), y = y(k), z = z(k);
        Room r = c.roomAt(x, y, z);
        if (r != null) return x - c.boxX(r) == r.variant.sizeX / 2 && z - c.boxZ(r) == r.variant.sizeZ / 2 && y - r.originY == 2;
        return Math.floorMod(x - c.anchorX, PITCH) == 3 || Math.floorMod(z - c.anchorZ, PITCH) == 3;
    }

    // ---- choosing the next room ----------------------------------------------------------------------------------

    /**
     * A room the dry run can build: {@code gx, gz} at {@code originY}, joined toward {@code -parentDir}.
     * {@code span} is 1 for the neighbour, or more when the corridor crosses blocked cells to a farther one.
     * {@code why} names the cells that did not seal, each with its first breach and the depth window.
     */
    public record Choice(int gx, int gz, int originY, int parentDir, int tried, String why, int span) {
        public Choice {
            if (span < 1) span = 1;
            if (why == null) why = "";
        }

        public Choice(int gx, int gz, int originY, int parentDir, int tried, String why) {
            this(gx, gz, originY, parentDir, tried, why, 1);
        }
    }

    /** Origin Y for a room under {@code surfaceY}: the backrooms rule (16 under the ground), clamped to the world. */
    public static int preferredY(int surfaceY, int minY, int maxY) {
        // Ostinato: depthOverride (setting mazeDepthY) pins the level at an absolute Y, e.g. deepslate at -40.
        int want = depthOverride != Integer.MIN_VALUE ? depthOverride : surfaceY - 16;
        return Math.max(minY + 1, Math.min(want, maxY - MAX_HEIGHT - MazeSeed.COVER - 1));
    }

    /** The first room: an empty complex anchored for it, and the room's origin Y. */
    public record First(Complex complex, int originY) {}

    /**
     * The first room: room 0,0 under the feet. Tries a few anchor shifts and depths around the backrooms depth, deeper
     * first on a tie, like {@link MazePlanner#place}. Null when none seals; {@code why} says which anchors failed.
     */
    public static First firstRoom(long seed, int feetX, int feetZ, int preferredY, int minY, int maxY, BlockProbe probe, List<String> why) {
        int[][] shifts = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {4, 0}, {0, 4}, {-4, 0}, {0, -4}};
        for (int[] sh : shifts) {
            Complex c = new Complex(seed, feetX - 3 + sh[0], feetZ - 3 + sh[1]);
            for (int y : depths(preferredY, preferredY - 18, preferredY + 4, minY, maxY)) {
                Segment s = first(c, 0, 0, y);
                if (breaches(s, probe, false).isEmpty()) return new First(c, y);
            }
            why.add("anchor " + (feetX - 3 + sh[0]) + "," + (feetZ - 3 + sh[1]) + "=no-seal");
        }
        return null;
    }

    /** Ys from {@code pref} outward inside [lo, hi] and the world, downward first on a tie (rooms belong under ground). */
    static List<Integer> depths(int pref, int lo, int hi, int minY, int maxY) {
        lo = Math.max(lo, minY + 1);
        hi = Math.min(hi, maxY - MAX_HEIGHT - MazeSeed.COVER - 1);
        List<Integer> out = new ArrayList<>();
        if (lo > hi) return out;
        pref = Math.max(lo, Math.min(hi, pref));
        out.add(pref);
        for (int d = 1; pref - d >= lo || pref + d <= hi; d++) {
            if (pref - d >= lo) out.add(pref - d);
            if (pref + d <= hi) out.add(pref + d);
        }
        return out;
    }

    /**
     * Ostinato: the next room. Free neighbours of the newest room first (a winding path, short walks between digs), then
     * of older rooms. Each candidate's depth starts at the backrooms depth under its own ground and is kept within
     * what the joining tunnel can climb. {@code surface} gives the ground height.
     * A neighbour that seals at no fully loaded depth is marked failed in {@code c}. Ostinato: run111. Those marks used
     * to stick, so the next call tried nothing and reported none free. When this call finds no seal, the older marks
     * are dropped and the search runs once more, which reaches the neighbours of earlier rooms. A breach that includes
     * an unloaded cell is not a failure.
     * <p>
     * Ostinato: run112. A breach that is prior-run carved air or our own structure is joined (the seal pass closes it)
     * or left for a cleaner depth. When every immediate neighbour still fails, the search walks a widening ring,
     * span 2 through {@link #MAX_SPAN}: a longer corridor through the blocked cell, with a stair rise past
     * {@link #MAX_RISE}, until a sealed room is found. Forever-expand does not stop on the four adjacent cells.
     */
    public static Choice next(Complex c, BlockProbe probe, IntBinaryOperator surface, int minY, int maxY, int maxTries) {
        Set<Long> before = new HashSet<>(c.failed);
        Choice ch = search(c, probe, surface, minY, maxY, maxTries, 1, 1);
        if (ch.parentDir() >= 0) return ch;
        if (!before.isEmpty()) {
            if (ch.tried() == 0) c.failed.clear(); // every neighbour was already marked; probe them again
            else c.failed.removeAll(before); // keep this call's new marks, retry the older frontier
            Choice again = search(c, probe, surface, minY, maxY, maxTries, 1, 1);
            if (again.parentDir() >= 0) return again;
            ch = new Choice(0, 0, 0, -1, ch.tried() + again.tried(), (ch.why() + " " + again.why()).trim(), 1);
        }
        // The adjacent ring is closed. Look further before giving up.
        Choice far = search(c, probe, surface, minY, maxY, Math.max(maxTries, 4 * (MAX_SPAN - 1)), 2, MAX_SPAN);
        if (far.parentDir() >= 0) {
            return new Choice(far.gx(), far.gz(), far.originY(), far.parentDir(), ch.tried() + far.tried(),
                    (ch.why() + " " + far.why()).trim(), far.span());
        }
        return new Choice(0, 0, 0, -1, ch.tried() + far.tried(), (ch.why() + " " + far.why()).trim(), 1);
    }

    /**
     * Rooms {@code minSpan}..{@code maxSpan} steps from a built room, newest parent first. Span 1 is the adjacent
     * neighbour. A taken choice has {@code parentDir >= 0}.
     */
    private static Choice search(Complex c, BlockProbe probe, IntBinaryOperator surface, int minY, int maxY, int maxTries,
                                 int minSpan, int maxSpan) {
        Set<Long> oldAir = c.air();
        List<Room> order = new ArrayList<>(c.rooms());
        java.util.Collections.reverse(order);
        StringBuilder why = new StringBuilder();
        int tried = 0;
        for (int span = minSpan; span <= maxSpan; span++) {
            for (Room parent : order) {
                int start = (int) Long.remainderUnsigned(MazeSeed.mix(c.seed ^ SALT, parent.gx * 31 + span, parent.gz), 4);
                for (int i = 0; i < 4; i++) {
                    int dir = (start + i) & 3;
                    int gx = parent.gx + DIR[dir][0] * span, gz = parent.gz + DIR[dir][1] * span;
                    if (!corridorFree(c, parent, dir, span, gx, gz)) continue;
                    if (tried >= maxTries) return new Choice(0, 0, 0, -1, tried, why.toString().trim(), span);
                    tried++;
                    int pref = preferredY(surface.applyAsInt(c.originX(gx) + 3, c.originZ(gz) + 3), minY, maxY);
                    if (probe.get(c.originX(gx) + 3, pref, c.originZ(gz) + 3) == BlockKind.UNKNOWN) {
                        why.append(' ').append(gx).append(',').append(gz).append("=unloaded");
                        continue; // not loaded yet: try again another time, never remembered as a failure
                    }
                    RoomVariant child = variant(c.seed, gx, gz);
                    List<Integer> ys = depthWindow(parent, child, dir, span, pref, minY, maxY);
                    int[] window = depthSpan(ys);
                    Integer clean = null, ours = null, cross = null;
                    Long blocked = null;
                    boolean unloaded = false;
                    for (int y : ys) {
                        Segment s = join(c, oldAir, parent, dir, y, span);
                        List<Long> holes = breaches(s, probe, false);
                        if (holes.isEmpty()) {
                            clean = y;
                            break; // nearest fully sealed depth
                        }
                        if (unloadedBreach(probe, holes)) {
                            unloaded = true;
                            continue;
                        }
                        long[] bad = new long[1];
                        int fit = rate(probe, s, parent.gx, parent.gz, gx, gz, span, holes, bad);
                        if (fit == FIT_OURS && ours == null) ours = y;
                        else if (fit == FIT_CROSS && cross == null) cross = y;
                        else if (fit == FIT_BLOCKED && blocked == null) blocked = bad[0];
                    }
                    if (clean != null) return new Choice(gx, gz, clean, dir, tried, why.toString().trim(), span);
                    if (ours != null) return new Choice(gx, gz, ours, dir, tried, why.toString().trim(), span);
                    if (cross != null) return new Choice(gx, gz, cross, dir, tried, why.toString().trim(), span);
                    if (unloaded) {
                        // A chunk edge is not proof. Still note a cave we did see at some other depth.
                        if (blocked != null) logBreach(why, gx, gz, span, blocked, probe, window[0], window[1]);
                        why.append(' ').append(gx).append(',').append(gz).append("=unloaded");
                        continue;
                    }
                    if (ys.isEmpty() || blocked == null) {
                        why.append(' ').append(gx).append(',').append(gz).append("=no-seal depths=none");
                        c.fail(gx, gz);
                        continue;
                    }
                    c.fail(gx, gz); // water, lava or open caves at every depth the tunnel can climb to
                    logBreach(why, gx, gz, span, blocked, probe, window[0], window[1]);
                }
            }
        }
        return new Choice(0, 0, 0, -1, tried, why.toString().trim(), minSpan);
    }

    /** No room, failure mark or existing corridor occupies the cell, and nothing stands in the straight line to it. */
    private static boolean corridorFree(Complex c, Room parent, int dir, int span, int gx, int gz) {
        if (c.room(gx, gz) != null || c.failed(gx, gz) || c.tunnelCrosses(gx, gz)) return false;
        for (int s = 1; s < span; s++) {
            int ix = parent.gx + DIR[dir][0] * s, iz = parent.gz + DIR[dir][1] * s;
            if (c.room(ix, iz) != null || c.tunnelCrosses(ix, iz)) return false;
        }
        return true;
    }

    /** Depths the tunnel can stair to: the capped window first, then anything {@link #stretchRise} adds past it. */
    private static List<Integer> depthWindow(Room parent, RoomVariant child, int dir, int span, int pref, int minY, int maxY) {
        int near = maxRise(parent.variant, child, dir, span);
        int far = stretchRise(parent.variant, child, dir, span);
        List<Integer> ys = new ArrayList<>(depths(pref, parent.originY - near, parent.originY + near, minY, maxY));
        if (far > near) {
            for (int y : depths(pref, parent.originY - far, parent.originY + far, minY, maxY)) {
                if (y < parent.originY - near || y > parent.originY + near) ys.add(y);
            }
        }
        return ys;
    }

    private static int[] depthSpan(List<Integer> ys) {
        if (ys.isEmpty()) return new int[]{0, 0};
        int lo = ys.get(0), hi = ys.get(0);
        for (int y : ys) {
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        return new int[]{lo, hi};
    }

    /** {@code gx,gz=no-seal [span=N] x,y,z:KIND depths=lo..hi} so a log can tell water from carved air. */
    private static void logBreach(StringBuilder why, int gx, int gz, int span, long hole, BlockProbe probe, int lo, int hi) {
        why.append(' ').append(gx).append(',').append(gz).append("=no-seal");
        if (span > 1) why.append(" span=").append(span);
        BlockKind kind = probe.get(x(hole), y(hole), z(hole));
        why.append(' ').append(x(hole)).append(',').append(y(hole)).append(',').append(z(hole));
        why.append(':').append(kind.name());
        why.append(" depths=").append(lo).append("..").append(hi);
    }

    private static final int FIT_BLOCKED = 1, FIT_OURS = 2, FIT_CROSS = 3;

    /**
     * A loaded breach list. Ours: every destination hole is prior carved air or foreign structure, so it can be sealed.
     * Cross: span &gt; 1 and the only holes are air in the cells the corridor passes through. Blocked: fluid, or a cave
     * on the room itself. {@code bad[0]} is the first blocking cell.
     */
    private static int rate(BlockProbe probe, Segment s, int pgx, int pgz, int cgx, int cgz, int span, List<Long> holes, long[] bad) {
        boolean ours = false, cross = false;
        for (long h : holes) {
            BlockKind kind = probe.get(x(h), y(h), z(h));
            if (span > 1 && onCorridor(s.before, pgx, pgz, cgx, cgz, h) && kind != BlockKind.FLUID) {
                cross = true; // air in the blocked cell the long corridor is allowed to cross and seal
                continue;
            }
            if (kind == BlockKind.FLUID || !priorCarved(probe, s, h)) {
                bad[0] = h;
                return FIT_BLOCKED;
            }
            ours = true;
        }
        if (ours) return FIT_OURS;
        if (cross) return FIT_CROSS;
        bad[0] = holes.get(0);
        return FIT_BLOCKED;
    }

    /** Grid cell of {@code h} sits strictly between the parent and the child, on the straight corridor. */
    private static boolean onCorridor(Complex c, int pgx, int pgz, int cgx, int cgz, long h) {
        int hx = Math.floorDiv(x(h) - c.anchorX, PITCH);
        int hz = Math.floorDiv(z(h) - c.anchorZ, PITCH);
        if (pgx == cgx) {
            int lo = Math.min(pgz, cgz), hi = Math.max(pgz, cgz);
            return hx == pgx && hz > lo && hz < hi;
        }
        if (pgz == cgz) {
            int lo = Math.min(pgx, cgx), hi = Math.max(pgx, cgx);
            return hz == pgz && hx > lo && hx < hi;
        }
        return false;
    }

    /**
     * True when this breach is air we carved (a prior run, or our own structure) rather than a cave or water.
     * Structure that belongs to the segment being planned does not count: that would make every neighbour of a
     * furnished room look like ours.
     */
    private static boolean priorCarved(BlockProbe probe, Segment s, long hole) {
        int hx = x(hole), hy = y(hole), hz = z(hole);
        BlockKind kind = probe.get(hx, hy, hz);
        if (kind == BlockKind.FLUID) return foreignStructure(probe, s, hx, hy, hz) || nearStructure(probe, s, hole);
        if (kind != BlockKind.AIR) return false;
        if (s.isAir(hole) || s.after.inBuild(hole)) return true;
        if (foreignStructure(probe, s, hx, hy, hz) || nearStructure(probe, s, hole)) return true;
        return roomShapedPocket(probe, s, hole);
    }

    /** A structure block that is not part of the complex already on the map. */
    private static boolean foreignStructure(BlockProbe probe, Segment s, int x, int y, int z) {
        long k = key(x, y, z);
        if (s.planned(k) || s.oldAir.contains(k)) return false;
        return probe.structure(x, y, z);
    }

    /** Structure within 10 blocks, ignoring the complex we are already building. Adjacent hits return first. */
    private static boolean nearStructure(BlockProbe probe, Segment s, long hole) {
        int x0 = x(hole), y0 = y(hole), z0 = z(hole);
        for (int r = 1; r <= 10; r++) {
            int yLo = Math.max(-4, -r), yHi = Math.min(4, r);
            for (int dy = yLo; dy <= yHi; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                        if (foreignStructure(probe, s, x0 + dx, y0 + dy, z0 + dz)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * A closed air pocket the size and shape of a room: not open to the sky or a cave system, and not a one-block
     * gap. Planned cells are walls, so the flood stays in the world outside the complex.
     */
    private static boolean roomShapedPocket(BlockProbe probe, Segment s, long start) {
        ArrayDeque<Long> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(start);
        seen.add(start);
        int minX = x(start), maxX = minX, minY = y(start), maxY = minY, minZ = z(start), maxZ = minZ;
        while (!q.isEmpty()) {
            if (seen.size() > POCKET_CAP) return false;
            long k = q.poll();
            int cx = x(k), cy = y(k), cz = z(k);
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minY = Math.min(minY, cy);
            maxY = Math.max(maxY, cy);
            minZ = Math.min(minZ, cz);
            maxZ = Math.max(maxZ, cz);
            for (int[] f : FACES) {
                long n = offset(k, f[0], f[1], f[2]);
                if (seen.contains(n) || s.planned(n)) continue;
                BlockKind kind = probe.get(x(n), y(n), z(n));
                if (kind == BlockKind.AIR) {
                    seen.add(n);
                    q.add(n);
                } else if (kind == BlockKind.FLUID || kind == BlockKind.UNKNOWN) {
                    return false;
                }
            }
        }
        int dx = maxX - minX + 1, dy = maxY - minY + 1, dz = maxZ - minZ + 1;
        if (seen.size() < 20 || dy > MAX_HEIGHT || dx > PITCH || dz > PITCH) return false;
        return seen.size() * 2 >= dx * dy * dz;
    }

    /** True when the segment can be built: sealed, or only prior carved air / a corridor crossing a blocked cell. */
    public static boolean buildable(Segment s, BlockProbe probe) {
        List<Long> holes = breaches(s, probe, false);
        if (holes.isEmpty()) return true;
        if (unloadedBreach(probe, holes)) return false;
        if (s.parent == null) return false;
        int span = Math.abs(s.room.gx - s.parent.gx) + Math.abs(s.room.gz - s.parent.gz);
        int fit = rate(probe, s, s.parent.gx, s.parent.gz, s.room.gx, s.room.gz, span, holes, new long[1]);
        return fit == FIT_OURS || fit == FIT_CROSS;
    }

    /** True when a breach cell was not loaded. That depth is not proof the neighbour can never seal. */
    private static boolean unloadedBreach(BlockProbe probe, List<Long> holes) {
        for (long k : holes) {
            if (probe.get(x(k), y(k), z(k)) == BlockKind.UNKNOWN) return true;
        }
        return false;
    }

    /** Text form of the complex for the per-world map file. */
    public static List<String> save(Complex c) {
        List<String> out = new ArrayList<>();
        out.add(MAP_HEADER);
        out.add("seed " + Long.toUnsignedString(c.seed));
        out.add("anchor " + c.anchorX + " " + c.anchorZ);
        // Ostinato: 2026-11-02. The variant id rides as a 6th field: older readers stop at the 5th, older files lack it (CLASSIC).
        for (Room r : c.rooms.values()) out.add("room " + r.gx + " " + r.gz + " " + r.originY + " " + r.doors + " " + r.variant.id);
        for (long f : c.failed) out.add("fail " + (int) (f >> 32) + " " + (int) f);
        return out;
    }

    public static final String MAP_HEADER = "ostinato-maze-connected 1";

    /** Reads {@link #save} text; null when there is no complex yet. */
    public static Complex load(List<String> lines) {
        long seed = 0;
        String[] a = null;
        List<String[]> rooms = new ArrayList<>(), fails = new ArrayList<>();
        for (String raw : lines) {
            String[] p = raw.trim().split(" ");
            switch (p[0]) {
                case "seed" -> seed = Long.parseUnsignedLong(p[1]);
                case "anchor" -> a = p;
                case "room" -> rooms.add(p);
                case "fail" -> fails.add(p);
                default -> { }
            }
        }
        if (a == null) return null;
        Complex c = new Complex(seed, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        for (String[] r : rooms) {
            c.put(new Room(Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3]), Integer.parseInt(r[4]),
                    r.length > 5 ? RoomVariant.byId(r[5]) : RoomVariant.CLASSIC));
        }
        for (String[] f : fails) c.fail(Integer.parseInt(f[1]), Integer.parseInt(f[2]));
        return c;
    }
}
