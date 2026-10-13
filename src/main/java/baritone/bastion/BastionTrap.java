package baritone.bastion;

import java.util.List;

/** Piglin hole trap: where to dig a 1-wide, 2-deep hole beside the camp spot. Pure, for tests. */
public final class BastionTrap {
    private BastionTrap() {}

    /**
     * A candidate column beside us. The hole is the two blocks under the column's surface cell.
     * @param open      the surface cell and the one above are air (a piglin can walk onto it)
     * @param mineable  both hole blocks can be broken (not bedrock/chest/gold/ore we care about)
     * @param walled    every side of both hole cells is solid (except none: a gap lets it walk out)
     * @param floored   the block under the hole is solid
     * @param lavaNear  lava within 2 of the hole
     * @param edge      beside an open drop over lava/void
     * @param piglinDist distance to the nearest calm piglin (they come for the bait)
     */
    public record Site(int dx, int dz, boolean open, boolean mineable, boolean walled, boolean floored, boolean lavaNear, boolean edge, double piglinDist) {
        public boolean usable() {
            return open && mineable && walled && floored && !lavaNear && !edge;
        }
    }

    /** The usable site nearest the piglins, skipping columns already used by another hole; null if none. */
    public static Site choose(List<Site> sites, List<int[]> taken) {
        Site best = null;
        for (Site s : sites) {
            if (!s.usable()) continue;
            boolean used = false;
            for (int[] t : taken) if (Math.abs(t[0] - s.dx()) + Math.abs(t[1] - s.dz()) <= 1) used = true; // separate, not adjacent
            if (used) continue;
            if (best == null || s.piglinDist() < best.piglinDist()) best = s;
        }
        return best;
    }
}