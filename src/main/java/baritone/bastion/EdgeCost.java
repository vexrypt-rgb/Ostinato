package baritone.bastion;

import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Path cost for standing next to an open drop over the lava sea (or void). The bastion router turns it on while it runs;
 * Traverse/Diagonal add {@link #PENALTY} to a destination cell with such a drop beside it. A cost, not a ban, so bridges
 * (all edge) stay passable.
 */
public final class EdgeCost {
    public static volatile boolean enabled;
    /** cells LavaFlow predicts lava will reach soon (LavaFlow.key); treated as lava by the bastion checks and priced out of paths */
    public static volatile java.util.Set<Long> lavaSoon = java.util.Set.of();
    public static final double FLOW_PENALTY = 200;
    public static final double PENALTY = 25;
    /** Air deeper than this onto lava or nothing is a deadly edge. */
    public static final int MIN_DROP = 3, SCAN = 16;

    private EdgeCost() {}

    public static double penalty(CalculationContext c, int x, int y, int z) {
        if (!enabled) return 0;
        java.util.Set<Long> soon = lavaSoon;
        if (!soon.isEmpty() && (soon.contains(LavaFlow.key(x, y, z)) || soon.contains(LavaFlow.key(x, y + 1, z)))) return FLOW_PENALTY;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            if (deadlyColumn(c, x + dx, y, z + dz)) return PENALTY;
        }
        return 0;
    }

    /** Below (x, y-1, z) is open air for more than MIN_DROP blocks and ends in lava or runs past SCAN. */
    static boolean deadlyColumn(CalculationContext c, int x, int y, int z) {
        for (int d = 1; d <= SCAN; d++) {
            BlockState s = c.get(x, y - d, z);
            if (MovementHelper.isLava(s)) return d > MIN_DROP;
            if (!s.getCollisionShape(c.bsi.access, new net.minecraft.core.BlockPos(x, y - d, z)).isEmpty()) return false;
        }
        return true;
    }

    /** Pure rule for tests: first lava/solid depth (-1 = nothing found in range). */
    public static boolean deadly(int firstLavaDepth, int firstSolidDepth) {
        if (firstSolidDepth > 0 && (firstLavaDepth < 0 || firstSolidDepth < firstLavaDepth)) return false;
        return firstLavaDepth < 0 || firstLavaDepth > MIN_DROP;
    }
}
