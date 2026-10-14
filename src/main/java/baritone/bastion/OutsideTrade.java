package baritone.bastion;

/** After the route: trade outside the bastion when brutes sit at the piglins. */
public final class OutsideTrade {
    private OutsideTrade() {}
    public enum Mode { INSIDE, LURE_OUT, WASTES, DONE }
    public static String setting = System.getProperty("ostinato.bastion.outsidetrade", "auto");

    /**
     * @param routeDone      the bastion route has finished
     * @param sufficient     the pearl/obsidian/string/fire-res sufficiency check already passes
     * @param freePiglins    adult piglins with no brute within 6
     * @param guardedPiglins adult piglins with a brute within 6 (or in a stall)
     * @param wastesDist     distance to the nearest wastes/crimson biome cell, or -1
     */
    public static Mode choose(String set, boolean routeDone, boolean sufficient, int freePiglins, int guardedPiglins, double wastesDist) {
        if (sufficient) return Mode.DONE;
        if ("off".equals(set) || !routeDone) return Mode.INSIDE;
        boolean force = "force".equals(set);
        if (!force && (freePiglins > 0 || guardedPiglins == 0)) return Mode.INSIDE;
        if (guardedPiglins >= 2 || force && guardedPiglins + freePiglins >= 2) return Mode.LURE_OUT;
        if (wastesDist >= 0 && wastesDist <= 160) return Mode.WASTES;
        return Mode.INSIDE;
    }

    /** Lure target count: 2-4 piglins. */
    public static int lureCount(int available) { return Math.max(0, Math.min(4, available)); }

    /** A lure spot is outside when it is beyond the bastion's footprint radius plus a margin. */
    public static boolean outside(double dx, double dz, double bastionRadius) { return Math.hypot(dx, dz) > bastionRadius + 8; }
}
