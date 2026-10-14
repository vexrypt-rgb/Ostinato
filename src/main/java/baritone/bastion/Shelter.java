package baritone.bastion;

import java.util.Arrays;

/**
 * Walling in: the cell we stand in, shut on all four sides and overhead with our own blocks.
 *
 * A brute comes for a player it can see within twelve blocks, swings at one it can see, and drops one it has lost sight
 * of within a second. It does not dig. A tower leaves us in plain sight, so the brute waits at its foot for as long as
 * we stand on it; behind two blocks on every side it gives up and walks back to its post. The walls go up from the
 * floor in ten clicks, under a ceiling as well as in the open, which a tower does not.
 *
 * Plain coordinates and a three-question view of the world, so the order of the clicks can be tested without a game.
 */
public final class Shelter {

    private Shelter() {}

    public interface Grid {
        /** A block can go here: air, a fluid, fire, grass. */
        boolean open(int x, int y, int z);

        /** A block that can be clicked to place another against it. */
        boolean solid(int x, int y, int z);

        /** Nothing can go here for now: somebody stands in the cell, or it has refused our clicks. */
        boolean blocked(int x, int y, int z);
    }

    /**
     * One block to place: the cell, the block to click and the face of it, and whether it is the block over our head,
     * which must not be one that falls.
     *
     * Not every face named here can be seen from inside: the top of a wall is over our eye, and the side of the floor
     * we stand on is under our feet. They are clicked all the same. Jumping for a look over the wall is what the wall
     * is there to prevent (arena trial 1: the brute had not noticed us until we did, and hit us in the air twice).
     */
    public static final class Place {
        public final int x;
        public final int y;
        public final int z;
        public final int ax;
        public final int ay;
        public final int az;
        public final int fx;
        public final int fy;
        public final int fz;
        public final boolean roof;
        public Place(int x, int y, int z, int ax, int ay, int az, int fx, int fy, int fz, boolean roof) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.fx = fx;
            this.fy = fy;
            this.fz = fz;
            this.roof = roof;
        }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public int ax() { return ax; }
        public int ay() { return ay; }
        public int az() { return az; }
        public int fx() { return fx; }
        public int fy() { return fy; }
        public int fz() { return fz; }
        public boolean roof() { return roof; }
        @Override public boolean equals(Object o) { if (this == o) return true; if (!(o instanceof Place)) return false; Place that__ = (Place) o; return java.util.Objects.equals(x, that__.x) && java.util.Objects.equals(y, that__.y) && java.util.Objects.equals(z, that__.z) && java.util.Objects.equals(ax, that__.ax) && java.util.Objects.equals(ay, that__.ay) && java.util.Objects.equals(az, that__.az) && java.util.Objects.equals(fx, that__.fx) && java.util.Objects.equals(fy, that__.fy) && java.util.Objects.equals(fz, that__.fz) && java.util.Objects.equals(roof, that__.roof); }
        @Override public int hashCode() { return java.util.Objects.hash(x, y, z, ax, ay, az, fx, fy, fz, roof); }
        @Override public String toString() { return "Place[x=" + x + ", y=" + y + ", z=" + z + ", ax=" + ax + ", ay=" + ay + ", az=" + az + ", fx=" + fx + ", fy=" + fy + ", fz=" + fz + ", roof=" + roof + "]"; }}

    static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    static final int[][] CORNERS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** Our height standing, less a hair: a body on a slab reaches into a third level of cells. */
    static final double BODY = 1.79;

    /** The lowest level of cells our body is in. */
    public static int low(double feetY) {
        return (int) Math.floor(feetY + 1e-4);
    }

    /** The highest level of cells our body is in. */
    public static int high(double feetY) {
        return (int) Math.floor(feetY + BODY);
    }

    /** Whether every cell beside our body is shut. */
    public static boolean walled(Grid g, int cx, double feetY, int cz) {
        for (int[] s : SIDES) {
            for (int y = low(feetY); y <= high(feetY); y++) {
                if (g.open(cx + s[0], y, cz + s[1])) return false;
            }
        }
        return true;
    }

    /** The level of cells our eye is in, standing. */
    public static int eye(double feetY) {
        return (int) Math.floor(feetY + 1.62);
    }

    /**
     * The level for a slit toward something whose eye is at eyeY. It sees us, and comes, when its eye and ours look
     * along the same level of cells. Standing on a slab ours is a level over that of a brute on the floor, and it came
     * as far as the wall and lost us there (arena trial 3); sneaking brings our eye down a third of a block, which on
     * a slab is its level. Our standing eye level where neither stance matches.
     */
    public static int slitLevel(double feetY, double eyeY) {
        int theirs = (int) Math.floor(eyeY), sneaking = (int) Math.floor(feetY + 1.27);
        return theirs != eye(feetY) && theirs == sneaking ? sneaking : eye(feetY);
    }

    /**
     * The cell to open as a slit toward (tx, tz): in the wall that faces it most squarely. With the wall under it, the
     * block on top of it and the corners either side, it is one block high and one wide: we see out and strike out,
     * and nothing grown gets in.
     */
    public static int[] slit(int cx, int level, int cz, double tx, double tz) {
        int[] s = Math.abs(tx) >= Math.abs(tz) ? SIDES[tx >= 0 ? 0 : 1] : SIDES[tz >= 0 ? 2 : 3];
        return new int[]{cx + s[0], level, cz + s[1]};
    }

    /** Whether the four corners are shut as high as the walls, and each wall has a block on top of it. */
    public static boolean fortified(Grid g, int cx, double feetY, int cz) {
        int lo = low(feetY), hi = high(feetY);
        for (int[] c : CORNERS) {
            for (int y = lo; y <= hi; y++) {
                if (g.open(cx + c[0], y, cz + c[1])) return false;
            }
        }
        for (int[] s : SIDES) {
            if (g.open(cx + s[0], hi + 1, cz + s[1])) return false;
        }
        return true;
    }

    /**
     * The next block of the ring, once the walls and the roof are up, or null: the four corners as high as the walls,
     * then a block on top of each wall.
     *
     * A brute's blow lands 0.83 of a block past its own body and no higher or lower than its body. With all eight
     * columns round us shut for the height of ours, there is nowhere it can stand that its blow lands from, whether
     * it sees us or not. That is what lets one block be taken out at eye level.
     */
    public static Place fort(Grid g, int cx, double feetY, int cz, double tx, double tz) {
        int lo = low(feetY), hi = high(feetY);
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> Double.compare(CORNERS[b][0] * tx + CORNERS[b][1] * tz, CORNERS[a][0] * tx + CORNERS[a][1] * tz));
        for (int i : order) {
            int dx = CORNERS[i][0], dz = CORNERS[i][1], x = cx + dx, z = cz + dz;
            for (int y = lo; y <= hi; y++) {
                if (!g.open(x, y, z) || g.blocked(x, y, z)) continue;
                if (g.solid(x, y - 1, z)) return new Place(x, y, z, x, y - 1, z, 0, 1, 0, false);
                // the walls either side of the corner
                if (g.solid(x, y, cz)) return new Place(x, y, z, x, y, cz, 0, 0, dz, false);
                if (g.solid(cx, y, z)) return new Place(x, y, z, cx, y, z, dx, 0, 0, false);
                if (g.solid(x + dx, y, z)) return new Place(x, y, z, x + dx, y, z, -dx, 0, 0, false);
                if (g.solid(x, y, z + dz)) return new Place(x, y, z, x, y, z + dz, 0, 0, -dz, false);
                if (g.solid(x, y + 1, z)) return new Place(x, y, z, x, y + 1, z, 0, -1, 0, false);
            }
        }
        Arrays.sort(order, (a, b) -> Double.compare(SIDES[b][0] * tx + SIDES[b][1] * tz, SIDES[a][0] * tx + SIDES[a][1] * tz));
        for (int i : order) {
            int dx = SIDES[i][0], dz = SIDES[i][1], x = cx + dx, z = cz + dz;
            if (!g.open(x, hi + 1, z) || g.blocked(x, hi + 1, z)) continue;
            if (g.solid(x, hi, z)) return new Place(x, hi + 1, z, x, hi, z, 0, 1, 0, false);
            if (g.solid(cx, hi + 1, cz)) return new Place(x, hi + 1, z, cx, hi + 1, cz, dx, 0, dz, false);
        }
        return null;
    }

    /** Whether the cell over our head is shut. */
    public static boolean roofed(Grid g, int cx, double feetY, int cz) {
        return !g.open(cx, high(feetY) + 1, cz);
    }

    /**
     * The next block to place, or null with nothing left that can be placed. The side facing (tx, tz) is closed first,
     * each side from the bottom up, and the roof last.
     */
    public static Place next(Grid g, int cx, double feetY, int cz, double tx, double tz) {
        int lo = low(feetY), hi = high(feetY);
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> Double.compare(SIDES[b][0] * tx + SIDES[b][1] * tz, SIDES[a][0] * tx + SIDES[a][1] * tz));
        for (int i : order) {
            int dx = SIDES[i][0], dz = SIDES[i][1], sx = cx + dx, sz = cz + dz;
            // a wall with nothing under it starts lower. Down a step or two the ground there holds it, and a brute
            // standing on that ground would still reach us. Deeper than that nothing comes up that side, and one
            // block hung from the side of our own floor is enough to carry the wall
            int depth = 0;
            while (depth < 3 && g.open(sx, lo - 1 - depth, sz)) depth++;
            int base = !g.open(sx, lo, sz) ? lo : depth >= 3 ? lo - 1 : lo - depth;
            for (int y = base; y <= hi; y++) {
                if (!g.open(sx, y, sz) || g.blocked(sx, y, sz)) continue;
                if (g.solid(sx, y - 1, sz)) return new Place(sx, y, sz, sx, y - 1, sz, 0, 1, 0, false);
                if (g.solid(sx + dx, y, sz + dz)) return new Place(sx, y, sz, sx + dx, y, sz + dz, -dx, 0, -dz, false);
                // the cells either side of it along the wall: dz, dx is the wall's own direction
                if (g.solid(sx + dz, y, sz + dx)) return new Place(sx, y, sz, sx + dz, y, sz + dx, -dz, 0, -dx, false);
                if (g.solid(sx - dz, y, sz - dx)) return new Place(sx, y, sz, sx - dz, y, sz - dx, dz, 0, dx, false);
                if (g.solid(sx, y + 1, sz)) return new Place(sx, y, sz, sx, y + 1, sz, 0, -1, 0, false);
                // our own column: the floor we stand on, or the slab under our feet
                if (g.solid(cx, y, cz)) return new Place(sx, y, sz, cx, y, cz, dx, 0, dz, false);
            }
        }
        int ry = hi + 1;
        if (!g.open(cx, ry, cz)) return null;
        if (!g.blocked(cx, ry, cz)) {
            if (g.solid(cx, ry + 1, cz)) return new Place(cx, ry, cz, cx, ry + 1, cz, 0, -1, 0, true);
            for (int[] s : SIDES) {
                if (g.solid(cx + s[0], ry, cz + s[1])) return new Place(cx, ry, cz, cx + s[0], ry, cz + s[1], -s[0], 0, -s[1], true);
            }
        }
        // nothing up there to hang the roof from: one more block on top of a wall, on the side away from the threat
        for (int k = order.length - 1; k >= 0; k--) {
            int sx = cx + SIDES[order[k]][0], sz = cz + SIDES[order[k]][1];
            if (g.solid(sx, hi, sz) && g.open(sx, ry, sz) && !g.blocked(sx, ry, sz)) return new Place(sx, ry, sz, sx, hi, sz, 0, 1, 0, false);
        }
        return null;
    }
}
