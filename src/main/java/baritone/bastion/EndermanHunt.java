package baritone.bastion;

/** Last fallback: no tradeable piglins, no wastes/crimson nearby, so hunt endermen in a warped forest from a 2-high gap. */
public final class EndermanHunt {
    private EndermanHunt() {}
    public static String setting = System.getProperty("ostinato.bastion.endermanhunt", "auto");
    public static int maxWarpedDist = Integer.getInteger("ostinato.bastion.warpeddist", 256);

    public static boolean go(String set, OutsideTrade.Mode trade, boolean freePiglins, double warpedDist, int pearls, int pearlTarget) {
        if ("off".equals(set) || pearls >= pearlTarget) return false;
        if ("force".equals(set)) return warpedDist >= 0;
        return !freePiglins && trade == OutsideTrade.Mode.INSIDE && warpedDist >= 0 && warpedDist <= maxWarpedDist;
    }

    /** A spot is safe when we stand in a 2-high gap: open feet and head, solid floor and solid ceiling right above the head. */
    public static boolean safeGap(boolean floorSolid, boolean feetOpen, boolean headOpen, boolean ceilingSolid, boolean lavaAdjacent) {
        return floorSolid && feetOpen && headOpen && ceilingSolid && !lavaAdjacent;
    }

    /** Provoke only one at a time, in reach soon: within 24 and none already angry. */
    public static boolean provoke(double dist, int angry) { return angry == 0 && dist <= 24; }
}
