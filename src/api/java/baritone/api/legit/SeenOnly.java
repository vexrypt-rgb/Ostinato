/*
 * Ostinato: seen-only block knowledge. Not part of upstream Baritone.
 */
package baritone.api.legit;

/**
 * Whether a block is legitimately knowable from one eye position.
 * <p>
 * A block is knowable only when all three are shown: at least one face open to air or fluid, a clear block-grid line
 * from the eye to that open cell, and light of at least {@link #MIN_LIGHT} on that cell. Anything else, including an
 * unknown cell on the line, is not knowable. Glass, slabs, and other partial blocks count as blocking, because this
 * check cannot show a sight line through them.
 * <p>
 * This is a block-grid traversal. {@code RayTraceUtils} needs a live entity and level, so it is not used here and
 * this class does not add a second Minecraft raycaster.
 */
public final class SeenOnly {

    /** Combined block and sky light. Zero is an unlit cavity. */
    public static final int MIN_LIGHT = 1;

    /** How far a sight line is followed, in cells, before it is given up as not shown. */
    public static final int MAX_CELLS = 256;

    private static final int[][] FACES = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private SeenOnly() {}

    public enum Cell {
        /** Empty. Does not block sight. */
        AIR,
        /** Water or lava. Does not block sight. Not a dry cave step. */
        FLUID,
        /** Blocks sight and covers a face. Includes partial blocks this check cannot see through. */
        OPAQUE,
        /** Not loaded, or not readable. Blocks sight. */
        UNKNOWN
    }

    /** A world the filter can ask about one cell at a time. No chunk scan lives here. */
    public interface View {
        Cell at(int x, int y, int z);

        /**
         * Combined light at the cell, 0 to 15. Return a negative number when the light cannot be read.
         */
        int light(int x, int y, int z);
    }

    /** Same packing as a block position key. Safe for negative coordinates. */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFFL);
    }

    /**
     * @return true only when {@code (x,y,z)} is shown to be seeable from {@code (eyeX, eyeY, eyeZ)}
     */
    public static boolean knowable(View view, double eyeX, double eyeY, double eyeZ, int x, int y, int z) {
        if (view == null || !finite(eyeX) || !finite(eyeY) || !finite(eyeZ)) {
            return false;
        }
        for (int[] face : FACES) {
            int ax = x + face[0];
            int ay = y + face[1];
            int az = z + face[2];
            Cell open = view.at(ax, ay, az);
            if (open != Cell.AIR && open != Cell.FLUID) {
                continue;
            }
            if (view.light(ax, ay, az) < MIN_LIGHT) {
                continue;
            }
            if (clear(view, eyeX, eyeY, eyeZ, ax + 0.5, ay + 0.5, az + 0.5)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Block-grid line from the eye to the open-cell center. The end cell must be air or fluid. Every cell the line
     * enters before that, and any cell touched when the line crosses a corner, must be air or fluid.
     */
    public static boolean clear(View view, double x0, double y0, double z0, double x1, double y1, double z1) {
        if (view == null || !finite(x0) || !finite(y0) || !finite(z0) || !finite(x1) || !finite(y1) || !finite(z1)) {
            return false;
        }
        int x = floor(x0);
        int y = floor(y0);
        int z = floor(z0);
        int endX = floor(x1);
        int endY = floor(y1);
        int endZ = floor(z1);
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : boundary(x0, dx);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : boundary(y0, dy);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : boundary(z0, dz);
        for (int n = 0; n < MAX_CELLS; n++) {
            if (!open(view.at(x, y, z))) {
                return false;
            }
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
            boolean xNext = tMaxX <= tMaxY && tMaxX <= tMaxZ;
            boolean yNext = tMaxY <= tMaxX && tMaxY <= tMaxZ;
            boolean zNext = tMaxZ <= tMaxX && tMaxZ <= tMaxY;
            if (!xNext && !yNext && !zNext) {
                return false;
            }
            int nx = x + (xNext ? stepX : 0);
            int ny = y + (yNext ? stepY : 0);
            int nz = z + (zNext ? stepZ : 0);
            // A tie crosses a corner. The cells beside that corner are not entered as the next cell, so each one
            // has to be open or the line is not shown to be clear.
            if (((xNext ? 1 : 0) + (yNext ? 1 : 0) + (zNext ? 1 : 0)) >= 2) {
                if (xNext && !open(view.at(nx, y, z))) {
                    return false;
                }
                if (yNext && !open(view.at(x, ny, z))) {
                    return false;
                }
                if (zNext && !open(view.at(x, y, nz))) {
                    return false;
                }
                if (xNext && yNext && !open(view.at(nx, ny, z))) {
                    return false;
                }
                if (xNext && zNext && !open(view.at(nx, y, nz))) {
                    return false;
                }
                if (yNext && zNext && !open(view.at(x, ny, nz))) {
                    return false;
                }
            }
            if (xNext) {
                x = nx;
                tMaxX += tDeltaX;
            }
            if (yNext) {
                y = ny;
                tMaxY += tDeltaY;
            }
            if (zNext) {
                z = nz;
                tMaxZ += tDeltaZ;
            }
        }
        return false;
    }

    private static boolean open(Cell cell) {
        return cell == Cell.AIR || cell == Cell.FLUID;
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** Fraction of the segment to the next voxel boundary along one axis. */
    private static double boundary(double s, double ds) {
        if (ds > 0) {
            return (Math.floor(s) + 1.0 - s) / ds;
        }
        return (s - Math.floor(s)) / -ds;
    }
}
