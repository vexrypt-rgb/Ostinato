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
    public record Place(int x, int y, int z, int ax, int ay, int az, int fx, int fy, int fz, boolean roof) {}

    static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
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
