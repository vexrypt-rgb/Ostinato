/*
 * Ostinato: new file (spectator learning). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.util.HashMap;
import java.util.Map;

/**
 * Ostinato: what spectated fights suggest for the autotune's whole-fight numbers, as priors only. A prior says where
 * winners of watched fights tended to be ("they started eating at 8.4 health"), with how many fights stand behind it.
 * The autotune may start its search from there; it never puts a value in force on this alone: its own fights and
 * its own sequential test (GSPRT) still decide (see {@code CombatSpsa}).
 * <p>
 * Thread-safe: the spectator's worker writes, the game thread reads.
 */
public final class SpectatePriors {
    /** Fights a prior needs before it is offered at all. */
    public static final int MIN_FIGHTS = 20;
    /** At most this many of a number's steps from its baseline. */
    public static final double MAX_STEPS = 2;

    private static final SpectatePriors INSTANCE = new SpectatePriors();

    public static SpectatePriors get() {
        return INSTANCE;
    }

    public static final class Prior {
        public final double value;
        public final int n;

        Prior(double value, int n) {
            this.value = value;
            this.n = n;
        }
    }

    private final Map<String, Prior> priors = new HashMap<>();
    private final Map<String, Map<String, Double>> styles = new HashMap<>(); // copies: the worker owns the live Habits
    private volatile boolean enabled;
    // Ostinato: the kit we fight with now (KitSignatures' label), so priors come from watched fighters of that kit
    private volatile String ownKit;

    public void enabled(boolean on) {
        enabled = on;
    }

    /**
     * Ostinato: the kit our own fights are fought with now, as {@link KitSignatures} labels it ({@code null}: not
     * known, the priors of all kits together are used).
     */
    public void ownKit(String kit) {
        ownKit = kit;
    }

    public String ownKit() {
        return ownKit;
    }

    /**
     * The prior for one tuned number by its name (heal.eatNear, ...), or null. Ostinato: with our kit known, only
     * fighters watched with the same kit count; a kit with too few watched fights gives none.
     */
    public synchronized Prior prior(String knob) {
        if (!enabled) return null;
        String kit = ownKit;
        Prior p = priors.get(kit == null ? knob : kit + "|" + knob);
        return p == null || p.n < MIN_FIGHTS ? null : p;
    }

    /**
     * How far, in the number's steps, the search should start from its baseline: the prior's distance shrunk by how
     * many fights stand behind it ({@code n / (n + 40)}) and held to {@link #MAX_STEPS}. 0 when there is no prior.
     */
    public double startSteps(String knob, int base, int step) {
        Prior p = prior(knob);
        if (p == null || step <= 0) return 0;
        double raw = (p.value - base) / step * ((double) p.n / (p.n + 40));
        return Math.max(-MAX_STEPS, Math.min(MAX_STEPS, raw));
    }

    synchronized void put(String knob, double value, int n) {
        priors.put(knob, new Prior(value, n));
    }

    /** Refreshes the priors from the fight book: the whole-fight numbers that a watched fight shows. */
    synchronized void update(FightBook book) {
        int n = book.winnerCount("eatHp");
        double eat = book.winnerMean("eatHp");
        if (n > 0 && !Double.isNaN(eat)) put("heal.eatNear", eat, n);
        int np = book.winnerCount("potHp");
        double pot = book.winnerMean("potHp");
        if (np > 0 && !Double.isNaN(pot)) put("pots.healAt", pot, np);
        // Ostinato: and per kit, from 1v1 fights only
        for (String kit : book.kitsSeen()) {
            int nk = book.winnerCount(kit, "eatHp");
            double ek = book.winnerMean(kit, "eatHp");
            if (nk > 0 && !Double.isNaN(ek)) put(kit + "|heal.eatNear", ek, nk);
            int npk = book.winnerCount(kit, "potHp");
            double pk = book.winnerMean(kit, "potHp");
            if (npk > 0 && !Double.isNaN(pk)) put(kit + "|pots.healAt", pk, npk);
        }
    }

    /** A watched player's style, by name, for when we meet them; null if never watched long enough. */
    public synchronized Map<String, Double> style(String name) {
        if (!enabled) return null;
        return styles.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Kept once the player has swung 20 times in reach of someone. */
    synchronized void style(String name, Habits h) {
        if (h.swings() >= 20) styles.put(name.toLowerCase(java.util.Locale.ROOT), h.traits());
    }

    /** Swing gap of a watched player, for seeding the shield's own measurement; 0 when unknown. */
    public int swingGap(String name) {
        Map<String, Double> h = style(name);
        Double g = h == null ? null : h.get("swingGap");
        return g == null || g.isNaN() ? 0 : (int) Math.round(g);
    }
}
