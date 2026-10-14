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

package baritone.process.portal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The lava pool portal, cell by cell, in coordinates of its own.
 * <p>
 * x runs along the shore, 0 to 3. z = -1 is the shore row (the rim), z = 0 the row of lava the portal stands in,
 * z = 1 and beyond the rest of the pool. y = 1 is the layer the lava and the rim are both in, so someone on the rim
 * has their feet at y = 2.
 * <pre>
 *   y=4   C  T  T  .        C = corner block (the top casts are poured against it)
 *   y=3   L  .  .  R        L, R, T = cast obsidian
 *   y=2   L  .  .  R        . = portal
 *   y=1   L  .  .  R        y=1 is the lava layer: the two middle cells are emptied by the first water
 *   y=0      F  F           F = pool floor, obsidian from the same water (or cast, in a pool one deep)
 *        x=0  1  2  3
 * </pre>
 * Behind the frame, on the rim: a pillar of three at x = 0 with the water on its top block's +x face, at (1,4,-1),
 * and a bar of two at (2,2,-1) and (3,2,-1). The water falls behind the frame and through it: over the edge at
 * (1,4,0) down the x = 1 column, and from (2,4,-1) onto the bar and off it down the x = 2 column. Every cell that is
 * cast then has water beside it.
 * <p>
 * Nothing here knows about Minecraft: a {@link Site} turns these cells into world positions.
 */
public final class PortalLayout {

    private PortalLayout() {}

    /** A side of a cell, in site directions. */
    public enum Face {
        UP(0, 1, 0), DOWN(0, -1, 0), PLUS_X(1, 0, 0), MINUS_X(-1, 0, 0), PLUS_Z(0, 0, 1), MINUS_Z(0, 0, -1);

        public final int dx, dy, dz;

        Face(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    /** A block position in site coordinates. */
    public static final class Cell {

        public final int x, y, z;

        public Cell(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Cell offset(Face f) {
            return new Cell(x + f.dx, y + f.dy, z + f.dz);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Cell && ((Cell) o).x == x && ((Cell) o).y == y && ((Cell) o).z == z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + "," + z + ")";
        }
    }

    /** Where the bot stands while it does something. */
    public enum Stand {
        /** On the rim at x = 1. */
        RIM,
        /** On the rim, sneaking out over the lava far enough to see the rim's own +z face. */
        EDGE,
        /** On the obsidian at (1,1,1), in the pool. */
        A,
        /** On the obsidian at (2,1,1). */
        B
    }

    /** Something clicked into place: {@code cell} appears when {@code face} of {@code support} is clicked. */
    public static final class Placement {

        public final String name;
        public final Cell cell;
        public final Cell support;
        public final Face face;
        public final Stand stand;

        Placement(String name, Cell cell, Cell support, Face face, Stand stand) {
            if (!support.offset(face).equals(cell)) {
                throw new IllegalArgumentException(name);
            }
            this.name = name;
            this.cell = cell;
            this.support = support;
            this.face = face;
            this.stand = stand;
        }

        @Override
        public String toString() {
            return name + " " + cell;
        }
    }

    private static Cell c(int x, int y, int z) {
        return new Cell(x, y, z);
    }

    private static Placement p(String name, Cell cell, Cell support, Face face, Stand stand) {
        return new Placement(name, cell, support, face, stand);
    }

    /** The shore row. Its top is what the bot first stands on. */
    public static final List<Cell> RIM = list(c(0, 1, -1), c(1, 1, -1), c(2, 1, -1), c(3, 1, -1));

    /** Feet of the bot on the rim. */
    public static final Cell RIM_FEET = c(1, 2, -1);

    /** The throwaway block that holds the first water on one half of the base; broken once that half has set. */
    public static final Placement BASE_BLOCK = p("base block", c(2, 1, 0), c(2, 1, -1), Face.PLUS_Z, Stand.EDGE);

    /** The first water. It turns the lava around and under it to obsidian, then runs into the broken block's cell. */
    public static final Placement BASE_WATER = p("base water", c(1, 1, 0), c(2, 1, 0), Face.MINUS_X, Stand.EDGE);

    /** The two cells the base water empties: the bottom row of the portal. */
    public static final List<Cell> PIT = list(c(1, 1, 0), c(2, 1, 0));

    /** Under the pit: the bottom of the frame. Lava here sets under the water; stone here has to be dug and cast. */
    public static final List<Cell> FLOOR = list(c(1, 0, 0), c(2, 0, 0));

    /** Pool cells the base water sets, which the bot then stands on. */
    public static final Cell STAND_A = c(1, 1, 1);
    public static final Cell STAND_B = c(2, 1, 1);

    /** Blocks stacked on the rim, in the order they go down. The third pillar block needs a jump. */
    public static final List<Placement> SHAPES = list(
            p("pillar 1", c(0, 2, -1), c(0, 1, -1), Face.UP, Stand.RIM),
            p("pillar 2", c(0, 3, -1), c(0, 2, -1), Face.UP, Stand.RIM),
            p("pillar 3", c(0, 4, -1), c(0, 3, -1), Face.UP, Stand.RIM),
            p("bar 1", c(3, 2, -1), c(3, 1, -1), Face.UP, Stand.RIM),
            p("bar 2", c(2, 2, -1), c(2, 1, -1), Face.UP, Stand.RIM)
    );

    /** The corner block the first top cast is poured against. */
    public static final Placement CORNER = p("corner", c(0, 4, 0), c(0, 4, -1), Face.PLUS_Z, Stand.A);

    /** The water every cast sets against. */
    public static final Placement MOLD_WATER = p("mold water", c(1, 4, -1), c(0, 4, -1), Face.PLUS_X, Stand.A);

    /**
     * The frame cells that are cast, in order, each with the face the lava is poured against. The two at y = 1 are
     * normally obsidian already and are only checked. The left side has to be done before the first top cell, which
     * cuts off the water in the x = 1 column, and each top or right cell is poured against the one before it.
     */
    public static final List<Placement> CASTS = list(
            p("left 1", c(0, 1, 0), c(0, 1, -1), Face.PLUS_Z, Stand.A),
            p("left 2", c(0, 2, 0), c(0, 2, -1), Face.PLUS_Z, Stand.A),
            p("left 3", c(0, 3, 0), c(0, 3, -1), Face.PLUS_Z, Stand.A),
            p("top 1", c(1, 4, 0), c(0, 4, 0), Face.PLUS_X, Stand.A),
            p("top 2", c(2, 4, 0), c(1, 4, 0), Face.PLUS_X, Stand.B),
            p("right 1", c(3, 1, 0), c(3, 1, -1), Face.PLUS_Z, Stand.B),
            p("right 2", c(3, 2, 0), c(3, 2, -1), Face.PLUS_Z, Stand.B),
            p("right 3", c(3, 3, 0), c(3, 2, 0), Face.UP, Stand.B)
    );

    /** Where the portal blocks appear. */
    public static final List<Cell> INTERIOR = list(
            c(1, 1, 0), c(2, 1, 0), c(1, 2, 0), c(2, 2, 0), c(1, 3, 0), c(2, 3, 0));

    /** Every cell that has to be obsidian for the portal to light. */
    public static final List<Cell> FRAME = list(
            c(1, 0, 0), c(2, 0, 0),
            c(0, 1, 0), c(0, 2, 0), c(0, 3, 0),
            c(1, 4, 0), c(2, 4, 0),
            c(3, 1, 0), c(3, 2, 0), c(3, 3, 0));

    /**
     * Cells that have to be empty before the build: where blocks, water and the portal go, where the bot stands and
     * jumps, and what it has to see through.
     */
    public static final List<Cell> CLEAR;

    static {
        List<Cell> clear = new ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            for (int x = 0; x <= 2; x++) {
                clear.add(c(x, y, -1));
                clear.add(c(x, y, 0));
            }
        }
        clear.add(c(3, 2, -1));
        clear.add(c(3, 2, 0));
        clear.add(c(3, 3, 0));
        for (int y = 2; y <= 3; y++) {
            clear.add(c(1, y, 1));
            clear.add(c(2, y, 1));
        }
        CLEAR = Collections.unmodifiableList(clear);
    }

    /** Blocks the build uses up: the base block, five on the rim and the corner. */
    public static final int BLOCKS_NEEDED = 2 + SHAPES.size();

    /** Buckets of lava poured in a pool two deep with lava at both ends of the row, plus the one taken along. */
    public static final int LAVA_NEEDED = 7;

    @SafeVarargs
    private static <T> List<T> list(T... items) {
        return Collections.unmodifiableList(Arrays.asList(items));
    }

    /**
     * A place and a way round for the layout. {@code out} points from the rim into the pool, {@code right} along
     * the shore in the direction x grows; a mirrored site has its right on the other hand.
     */
    public static final class Site {

        /** World position of site cell (0,0,0). */
        public final int ox, oy, oz;
        public final int rightX, rightZ;
        public final int outX, outZ;

        public Site(int ox, int oy, int oz, int outX, int outZ, boolean mirrored) {
            if (Math.abs(outX) + Math.abs(outZ) != 1) {
                throw new IllegalArgumentException("out must be one of the four horizontal directions");
            }
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.outX = outX;
            this.outZ = outZ;
            // facing the pool, an unmirrored site has x growing to the right hand, as in the guide
            this.rightX = mirrored ? outZ : -outZ;
            this.rightZ = mirrored ? -outX : outX;
        }

        /** The eight ways round, for a given position of cell (0,0,0). */
        public static List<Site> orientations(int ox, int oy, int oz) {
            List<Site> all = new ArrayList<>(8);
            int[][] outs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
            for (int[] out : outs) {
                all.add(new Site(ox, oy, oz, out[0], out[1], false));
                all.add(new Site(ox, oy, oz, out[0], out[1], true));
            }
            return all;
        }

        /** The site, of this orientation, whose cell {@code anchor} is at the given world position. */
        public Site anchoredAt(Cell anchor, int wx, int wy, int wz) {
            return new Site(
                    wx - rightX * anchor.x - outX * anchor.z,
                    wy - anchor.y,
                    wz - rightZ * anchor.x - outZ * anchor.z,
                    outX, outZ, isMirrored());
        }

        public boolean isMirrored() {
            return rightX == outZ && rightZ == -outX;
        }

        public int worldX(int x, int z) {
            return ox + rightX * x + outX * z;
        }

        public int worldY(int y) {
            return oy + y;
        }

        public int worldZ(int x, int z) {
            return oz + rightZ * x + outZ * z;
        }

        public int worldX(Cell c) {
            return worldX(c.x, c.z);
        }

        public int worldY(Cell c) {
            return worldY(c.y);
        }

        public int worldZ(Cell c) {
            return worldZ(c.x, c.z);
        }

        /** World x of a point given in site coordinates, where cell i covers i to i + 1. */
        public double pointX(double x, double z) {
            return ox + 0.5 + rightX * (x - 0.5) + outX * (z - 0.5);
        }

        public double pointZ(double x, double z) {
            return oz + 0.5 + rightZ * (x - 0.5) + outZ * (z - 0.5);
        }

        /** The site cell at a world position. */
        public Cell cellAt(int wx, int wy, int wz) {
            int dx = wx - ox;
            int dz = wz - oz;
            return new Cell(dx * rightX + dz * rightZ, wy - oy, dx * outX + dz * outZ);
        }

        /** World step of a site face, as {dx, dy, dz}. */
        public int[] worldStep(Face f) {
            return new int[]{rightX * f.dx + outX * f.dz, f.dy, rightZ * f.dx + outZ * f.dz};
        }

        @Override
        public String toString() {
            return "site at " + worldX(RIM_FEET) + " " + worldY(RIM_FEET) + " " + worldZ(RIM_FEET)
                    + " facing " + (outX == 1 ? "east" : outX == -1 ? "west" : outZ == 1 ? "south" : "north")
                    + (isMirrored() ? " (mirrored)" : "");
        }
    }
}
