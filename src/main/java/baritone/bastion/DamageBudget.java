package baritone.bastion;

/** How many hits we may soak while luring or sealing mobs into a trade hole or brute pit, instead of retreating. */
public final class DamageBudget {
    private DamageBudget() {}

    /** Normal-difficulty brute axe hit, before armour. */
    public static final double BRUTE_HIT = 13.0;
    public static double floor = Double.parseDouble(System.getProperty("ostinato.bastion.hpfloor", "8"));
    public static int maxHits = Integer.getInteger("ostinato.bastion.maxhits", 2);

    /** May we accept {@code expectedHits} hits of {@code perHit} damage now? */
    public static boolean allow(double hp, double perHit, int expectedHits, boolean fire, boolean lavaNear, boolean fallRisk, int food) {
        if (expectedHits > maxHits || fire || lavaNear || fallRisk || food < 14) return false;
        return hp - perHit * expectedHits >= floor;
    }

    /** Abort the trap and retreat once below the floor, or when one more hit would take us under it. */
    public static boolean abort(double hp, double perHit) {
        return hp < floor || hp - perHit < floor * 0.5;
    }
}
