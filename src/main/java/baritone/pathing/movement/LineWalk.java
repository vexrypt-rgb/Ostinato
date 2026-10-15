package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;

/**
 * A straight walk across a knight offset (2 by 1). The corridor is the whole rectangle, which is
 * wider than the body, so a line is only offered in the open. Narrow corridors keep the grid path.
 */
public final class LineWalk {

    private LineWalk() {}

    public static double ticks(int dx, int dz, boolean sprint) {
        double per = sprint ? ActionCosts.SPRINT_ONE_BLOCK_COST : ActionCosts.WALK_ONE_BLOCK_COST;
        return Math.hypot(dx, dz) * per;
    }

    /** Cells other than the start, in sign-adjusted offsets, that the rectangle covers. */
    public static int[][] corridor(int dx, int dz) {
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        int n = (ax + 1) * (az + 1) - 1;
        int[][] cells = new int[n][2];
        int k = 0;
        for (int x = 0; x <= ax; x++) {
            for (int z = 0; z <= az; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                cells[k][0] = x * sx;
                cells[k][1] = z * sz;
                k++;
            }
        }
        return cells;
    }
}
