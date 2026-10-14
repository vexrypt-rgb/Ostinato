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
    public static final class Site {
        public final int dx;
        public final int dz;
        public final boolean open;
        public final boolean mineable;
        public final boolean walled;
        public final boolean floored;
        public final boolean lavaNear;
        public final boolean edge;
        public final double piglinDist;
        public Site(int dx, int dz, boolean open, boolean mineable, boolean walled, boolean floored, boolean lavaNear, boolean edge, double piglinDist) {
            this.dx = dx;
            this.dz = dz;
            this.open = open;
            this.mineable = mineable;
            this.walled = walled;
            this.floored = floored;
            this.lavaNear = lavaNear;
            this.edge = edge;
            this.piglinDist = piglinDist;
        }
        public int dx() { return dx; }
        public int dz() { return dz; }
        public boolean open() { return open; }
        public boolean mineable() { return mineable; }
        public boolean walled() { return walled; }
        public boolean floored() { return floored; }
        public boolean lavaNear() { return lavaNear; }
        public boolean edge() { return edge; }
        public double piglinDist() { return piglinDist; }
        @Override public boolean equals(Object o) { if (this == o) return true; if (!(o instanceof Site)) return false; Site that__ = (Site) o; return java.util.Objects.equals(dx, that__.dx) && java.util.Objects.equals(dz, that__.dz) && java.util.Objects.equals(open, that__.open) && java.util.Objects.equals(mineable, that__.mineable) && java.util.Objects.equals(walled, that__.walled) && java.util.Objects.equals(floored, that__.floored) && java.util.Objects.equals(lavaNear, that__.lavaNear) && java.util.Objects.equals(edge, that__.edge) && java.util.Objects.equals(piglinDist, that__.piglinDist); }
        @Override public int hashCode() { return java.util.Objects.hash(dx, dz, open, mineable, walled, floored, lavaNear, edge, piglinDist); }
        @Override public String toString() { return "Site[dx=" + dx + ", dz=" + dz + ", open=" + open + ", mineable=" + mineable + ", walled=" + walled + ", floored=" + floored + ", lavaNear=" + lavaNear + ", edge=" + edge + ", piglinDist=" + piglinDist + "]"; }
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