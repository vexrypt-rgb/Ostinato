package baritone.end;

import net.minecraft.util.Mth;

/**
 * Where a zero-cycle stands, and the bed rig it builds there.
 *
 * The 24 path nodes and the ten pillar centres are the positions the 26.3 fight uses. Node x and z
 * come from {@code Mth}'s sine table, not from {@code Math.cos} of the same angle. The holding pattern
 * then aims at an outer node: one step from the closest node, and one roll in eight also jumps six
 * and reverses direction. That common step is a diagonal only when the closest index modulo 3 is 2.
 * On a flat podium the four inner nodes tie and the earliest wins, so the common target is the back
 * diagonal and the rare one is the south straight. Real terrain moves the closest node.
 *
 * One roll in (living crystals + 3) perches before any of this. This class does not roll that.
 *
 * The stand is the public front-tower cheat sheet: from the outer corner, west and north by the tower
 * class. A back tower uses the same steps on the opposite corner. A side node is not that sheet.
 * The rig is the staircase from the same notes. Neither has been stood on in a world, and nothing here places a block.
 */
public final class ZeroCycle {

    /** Outer, middle, inner. */
    public static final int NODES = 24;
    public static final int OUTER = 12;
    public static final int PILLARS = 10;
    /** Node height is at least this, which is the end's sea level plus ten. */
    public static final int NODE_FLOOR = 73;
    /** The obsidian platform the player arrives on. Front is toward this. */
    public static final int PLATFORM_X = 100;
    public static final int PLATFORM_Z = 0;

    /** Float constants from the fight's node loop, not a recomputed pi. */
    private static final float NEG_PI = -3.1415927f;
    private static final float OUTER_STEP = 0.2617994f;
    private static final float MIDDLE_STEP = 0.3926991f;
    private static final float INNER_STEP = 0.7853982f;

    private static final int[] NODE_X = new int[NODES];
    private static final int[] NODE_Z = new int[NODES];
    private static final int[] PILLAR_X = new int[PILLARS];
    private static final int[] PILLAR_Z = new int[PILLARS];

    static {
        for (int i = 0; i < NODES; i++) {
            float radius;
            float step;
            int k;
            if (i < 12) {
                radius = 60.0f;
                step = OUTER_STEP;
                k = i;
            } else if (i < 20) {
                radius = 40.0f;
                step = MIDDLE_STEP;
                k = i - 12;
            } else {
                radius = 20.0f;
                step = INNER_STEP;
                k = i - 20;
            }
            float angle = 2.0f * (NEG_PI + step * k);
            NODE_X[i] = Mth.floor(radius * Mth.cos(angle));
            NODE_Z[i] = Mth.floor(radius * Mth.sin(angle));
        }
        for (int i = 0; i < PILLARS; i++) {
            double angle = 2.0 * (-Math.PI + (Math.PI / 10.0) * i);
            PILLAR_X[i] = Mth.floor(42.0 * Math.cos(angle));
            PILLAR_Z[i] = Mth.floor(42.0 * Math.sin(angle));
        }
    }

    private ZeroCycle() {}

    public static int nodeX(int i) {
        return NODE_X[i];
    }

    public static int nodeZ(int i) {
        return NODE_Z[i];
    }

    /** Added to the surface before the floor of 73. The middle ring is ten higher. */
    public static int nodeLift(int i) {
        return i >= 12 && i < 20 ? 15 : 5;
    }

    public static int nodeY(int i, int surfaceY) {
        return Math.max(NODE_FLOOR, surfaceY + nodeLift(i));
    }

    public static int pillarX(int i) {
        return PILLAR_X[i];
    }

    public static int pillarZ(int i) {
        return PILLAR_Z[i];
    }

    /** Radius of a pillar whose shuffled rank is 0 through 9. Three ranks share each radius, then the last is wider. */
    public static int radius(int rank) {
        return 2 + rank / 3;
    }

    /** Obsidian top of that rank. 76, then three blocks taller each rank. */
    public static int pillarHeight(int rank) {
        return 76 + rank * 3;
    }

    /** Spike closest to that node in x and z. A tie keeps the earlier pillar. Height is still measured, not this. */
    public static int nearestPillar(int node) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        int x = NODE_X[node];
        int z = NODE_Z[node];
        for (int i = 0; i < PILLARS; i++) {
            long dx = (long) PILLAR_X[i] - x;
            long dz = (long) PILLAR_Z[i] - z;
            long d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Iron bars. Ranks 1 and 2 only. */
    public static boolean caged(int rank) {
        return rank == 1 || rank == 2;
    }

    /**
     * The outer node the holding pattern flies to. {@code clockwise} is the direction it had before this choice.
     * One in eight flips that direction and adds six first.
     */
    public static int holdingTarget(int closest, boolean clockwise, boolean oneInEight) {
        int next = closest;
        if (oneInEight) {
            clockwise = !clockwise;
            next += 6;
        }
        next += clockwise ? 1 : -1;
        next %= OUTER;
        if (next < 0) {
            next += OUTER;
        }
        return next;
    }

    /**
     * Closest node while a crystal is alive. {@code surfaceY[i]} is the motion-blocking height under node i.
     * The position is floored first, and a tie keeps the earlier index. After the last crystal the fight
     * starts this scan at node 12. A node past 10000 squared blocks is ignored there and the fight returns
     * 0; a caller that far from the island is outside this method.
     */
    public static int closest(double x, double y, double z, int[] surfaceY) {
        return closest(x, y, z, surfaceY, true);
    }

    public static int closest(double x, double y, double z, int[] surfaceY, boolean crystalsAlive) {
        int qx = Mth.floor(x);
        int qy = Mth.floor(y);
        int qz = Mth.floor(z);
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = crystalsAlive ? 0 : OUTER; i < NODES; i++) {
            double dx = NODE_X[i] - qx;
            double dy = nodeY(i, surfaceY[i]) - qy;
            double dz = NODE_Z[i] - qz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    public enum Approach {
        FRONT_DIAGONAL,
        BACK_DIAGONAL,
        FRONT_STRAIGHT,
        BACK_STRAIGHT,
        SIDE
    }

    /** An outer node on a cardinal axis. The two side ones have no front or back in {@link #approach}. */
    public static boolean straight(int node) {
        return node < OUTER && node % 3 == 0;
    }

    /** Front is the side toward the arrival platform, which is east. */
    public static Approach approach(int node) {
        int x = NODE_X[node];
        if (x > 15) {
            return straight(node) ? Approach.FRONT_STRAIGHT : Approach.FRONT_DIAGONAL;
        }
        if (x < -15) {
            return straight(node) ? Approach.BACK_STRAIGHT : Approach.BACK_DIAGONAL;
        }
        return Approach.SIDE;
    }

    public enum Tower { SMALL, CAGE, MEDIUM, BIG }

    /** 76 is the bare small tower. 79 and 82 are the cages. 85 through 91 are medium. Taller is big. */
    public static Tower tower(int height) {
        if (height <= 76) {
            return Tower.SMALL;
        }
        if (height <= 82) {
            return Tower.CAGE;
        }
        if (height <= 91) {
            return Tower.MEDIUM;
        }
        return Tower.BIG;
    }

    /**
     * The block to stand on. Diagonals use 95 on the five short towers and 99 from 91 up.
     * A straight is two lower.
     */
    public static int standY(Approach approach, int height) {
        int diagonal = height <= 88 ? 95 : 99;
        boolean straight = approach == Approach.FRONT_STRAIGHT || approach == Approach.BACK_STRAIGHT;
        return straight ? diagonal - 2 : diagonal;
    }

    /**
     * Horizontal stand, from the corner of the pillar top that faces away from the podium on that side.
     * Front steps west and north off the northwest corner. Back steps east and south off the southeast corner.
     */
    public static int[] standXZ(int centerX, int centerZ, int radius, Tower tower, Approach approach) {
        boolean front = approach == Approach.FRONT_DIAGONAL || approach == Approach.FRONT_STRAIGHT;
        int sign = front ? -1 : 1;
        int along;
        int side;
        switch (tower) {
            case SMALL:
                along = 3;
                side = 1;
                break;
            case CAGE:
                along = 3;
                side = 0;
                break;
            case MEDIUM:
                along = 2;
                side = 1;
                break;
            default:
                along = 1;
                side = 1;
                break;
        }
        return new int[]{
                centerX + sign * radius + sign * along,
                centerZ + sign * radius + sign * side
        };
    }

    public enum Piece { BLOCK, BED, ANCHOR }

    /**
     * The staircase, in steps forward, steps left, and steps up from the block stood on.
     * Two rising blocks, one to the left, two on top of that, then an anchor, a bed, and an anchor.
     */
    public static Piece[] rigPieces() {
        return new Piece[]{
                Piece.BLOCK, Piece.BLOCK, Piece.BLOCK, Piece.BLOCK, Piece.BLOCK,
                Piece.ANCHOR, Piece.BED, Piece.ANCHOR
        };
    }

    public static int[][] rigSteps() {
        return new int[][]{
                {1, 0, 1},
                {2, 0, 2},
                {2, 1, 2},
                {2, 1, 3},
                {2, 1, 4},
                {3, 1, 3},
                {4, 1, 3},
                {5, 1, 3}
        };
    }

    /**
     * World block of each rig step. Forward is the cardinal toward the node and left is the player's left.
     * The block stood on is not in the list, and nothing is placed.
     */
    public static int[][] rigAt(int standX, int standY, int standZ, int nodeX, int nodeZ) {
        int[] facing = forward(standX, standZ, nodeX, nodeZ);
        int[] side = left(facing[0], facing[1]);
        int[][] steps = rigSteps();
        int[][] world = new int[steps.length][3];
        for (int i = 0; i < steps.length; i++) {
            world[i][0] = standX + steps[i][0] * facing[0] + steps[i][1] * side[0];
            world[i][1] = standY + steps[i][2];
            world[i][2] = standZ + steps[i][0] * facing[1] + steps[i][1] * side[1];
        }
        return world;
    }

    /** Cardinal step from the stand toward the node. Left is the player's left while facing that way. */
    public static int[] forward(int standX, int standZ, int nodeX, int nodeZ) {
        int dx = nodeX - standX;
        int dz = nodeZ - standZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return new int[]{Integer.signum(dx), 0};
        }
        return new int[]{0, Integer.signum(dz)};
    }

    public static int[] left(int forwardX, int forwardZ) {
        return new int[]{forwardZ, -forwardX};
    }
}
