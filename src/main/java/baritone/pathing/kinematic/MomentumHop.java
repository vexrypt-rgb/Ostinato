package baritone.pathing.kinematic;

import java.util.HashSet;
import java.util.Set;

/**
 * Ostinato: multi-hop momentum under a low ceiling. Under a 2-high ceiling a single jump from a short run-up can't
 * clear a 2-block gap, but repeated head-hitter hops (3-tick cycles) build sprint speed past walking speed, and a
 * well-timed last takeoff at the edge carries over. This searches, in the sim, which ground ticks to jump on (holding
 * W + sprint straight ahead) so the player ends up standing on the destination block. Depth-first, jump tried first,
 * with visited-state pruning and a node budget so it stays cheap enough for the planner and for per-tick replanning.
 */
public final class MomentumHop {
    private MomentumHop() {}

    /** Ticks searched at most, and sim ticks spent at most per search. */
    public static final int MAX_TICKS = 60, BUDGET = 40000, PLAN_BUDGET = 6000;

    /**
     * @return the jump pattern from {@code start} ('J' = press jump this tick, '.' = don't), ending on the tick the
     * player stands on dest, or null if none was found
     */
    public static String find(PlayerSim.World w, PlayerSim start, int dirX, int dirZ, int destX, int destY, int destZ, boolean jumpedLastTick) {
        return find(w, start, dirX, dirZ, destX, destY, destZ, jumpedLastTick, BUDGET);
    }

    /** As above with a sim-tick budget (the planner uses a smaller one). */
    public static String find(PlayerSim.World w, PlayerSim start, int dirX, int dirZ, int destX, int destY, int destZ, boolean jumpedLastTick, int budget) {
        return find(w, start, dirX, dirZ, destX, destY, destZ, jumpedLastTick, budget, false);
    }

    /** Ostinato: as above, searched in the native library when {@code nativeSearch} and it is loaded ({@link NativePlanners}); the answer is the same. */
    public static String find(PlayerSim.World w, PlayerSim start, int dirX, int dirZ, int destX, int destY, int destZ, boolean jumpedLastTick, int budget,
                              boolean nativeSearch) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
        if (nativeSearch) {
            NativePlanners.Trail t = NativePlanners.momentumHop(w, start, yaw, dirX, dirZ, destX, destY, destZ, jumpedLastTick, budget);
            if (t != null) {
                if (NativePlanners.verify) {
                    NativePlanners.checked.incrementAndGet();
                    String ref = find(w, start, dirX, dirZ, destX, destY, destZ, jumpedLastTick, budget, false);
                    if (!java.util.Objects.equals(ref, t.trail())) {
                        throw new AssertionError("native momentum hop " + t.trail() + " differs from Java " + ref);
                    }
                }
                return t.trail();
            }
        }
        Search s = new Search(w, yaw, dirX, dirZ, destX, destY, destZ, Math.min(start.y, destY) - 0.6);
        s.budget = budget;
        StringBuilder trail = new StringBuilder();
        return s.dfs(start, 0, jumpedLastTick, trail) ? trail.toString() : null;
    }

    private static final class Search {
        final PlayerSim.World w;
        final float yaw;
        final int dirX, dirZ, destX, destY, destZ;
        final double floorY, beyond;
        final Set<Long> seen = new HashSet<>();
        int spent, budget = BUDGET;

        Search(PlayerSim.World w, float yaw, int dirX, int dirZ, int destX, int destY, int destZ, double floorY) {
            this.w = w;
            this.yaw = yaw;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.destX = destX;
            this.destY = destY;
            this.destZ = destZ;
            this.floorY = floorY;
            this.beyond = (destX + 0.5 + 0.5 * dirX) * dirX + (destZ + 0.5 + 0.5 * dirZ) * dirZ + 1.0;
        }

        boolean on(PlayerSim p) {
            return p.onGround && PlayerSim.floor(p.x) == destX && PlayerSim.floor(p.z) == destZ && PlayerSim.feetCell(p.y) == destY;
        }

        boolean dfs(PlayerSim p, int t, boolean prev, StringBuilder trail) {
            if (on(p)) return true;
            if (p.y < floorY || t >= MAX_TICKS || spent > budget) return false;
            if (p.x * dirX + p.z * dirZ > beyond) return false;
            long key = (((long) Math.round((p.x * dirX + p.z * dirZ) * 200) & 0xFFFFF) << 40)
                    ^ (((long) Math.round(p.y * 200) & 0xFFFF) << 24)
                    ^ (((long) Math.round((p.vx * dirX + p.vz * dirZ) * 2000) & 0xFFF) << 12)
                    ^ (((long) Math.round(p.vy * 1000) & 0x7FF) << 1) ^ (prev ? 1 : 0);
            if (!seen.add(key)) return false;
            boolean[] opts = p.onGround && !prev ? new boolean[]{true, false} : new boolean[]{false};
            for (boolean j : opts) {
                PlayerSim c = new PlayerSim(w);
                c.copyFrom(p);
                c.tick(yaw, true, true, j);
                spent++;
                trail.append(j ? 'J' : '.');
                if (dfs(c, t + 1, j, trail)) return true;
                trail.setLength(trail.length() - 1);
            }
            return false;
        }
    }
}
