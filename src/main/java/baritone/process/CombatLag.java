package baritone.process;

import java.util.Locale;

/**
 * How late the server is, as the fight itself shows it: the ticks from a click of ours to the foe's hurt flash, less
 * what the same takes with the server in our own process. A far server and a slow one both show in it, in the unit a
 * fight is decided in. Until enough hits have landed the server's own figure for us stands in.
 * <p>
 * Nothing here moves a decision. {@link CombatOutcome} credits our hits by {@link #band}, and the autotune keeps what
 * it learns apart by it: a number found good with the server next door says nothing about one a tenth of a second off.
 */
final class CombatLag {

    /**
     * PROVISIONAL — PENDING IN-GAME CALIBRATION. Every number of the measure, in one place. None of them has been
     * checked against a remote server: what stands behind them is the integrated server, 1906 hits of ours replayed
     * from the fights of 2026-10-12 with four kits (bench runs smoke3, crit1 and uhc2).
     */
    static final class Rules {
        /**
         * Ticks from click to flash with no latency at all, as {@link Rule#cluster} reads them. The flash came one
         * tick after the click or two and nothing else, the ones being 55% to 81% of them by kit: a mean of 1.18 to
         * 1.50. Higher hides the first tick of lag, lower shows one that is not there. To change it: the same replay
         * over more kits.
         */
        static final double LOCAL = 1.35;
        /**
         * Ticks a click waits for its flash; one later is some other hurt and is not counted. Twelve ticks hold a
         * round trip of half a second. Lower loses the hits of a far server and reads it as near, higher counts fire
         * and other hands as slow hits of ours. To change it: the share of our hits that flash later, from a server
         * known to be that far.
         */
        static final int WAIT = 12;
        /** Hits the measure is made of, the newest. Fewer follow a change sooner and scatter more. */
        static final int KEEP = 16;
        /** Hits before the measure is believed over the server's figure. */
        static final int ENOUGH = 6;
        /**
         * Ticks the measure has to stand off a band before it is left for another: at half a tick a round trip that
         * falls between two bands would change band with every hit, and a window that spans two is not judged.
         */
        static final double HOLD = 0.75;
        /** The last band, for this and everything later: 300 ms, past which the bands would each see too few fights. */
        static final int TOP = 6;
        /** Milliseconds to a tick, for the server's figure. */
        static final double TICK_MS = 50;

        private Rules() {}
    }

    /** The rules on their own, in a class that loads without the game. */
    static final class Rule {
        private Rule() {}

        /**
         * The mean of the first {@code n} delays that fall in the fullest two neighbouring ticks, the earlier pair of
         * two as full; NaN of none. Our own hits answer within two ticks of one another whatever the latency. A flash
         * that was some other hurt can lie anywhere in the wait: a foe standing in lava flashed eleven ticks after a
         * fifth of our clicks, and the middle half of the delays read that as three ticks of lag on a server with none.
         */
        static double cluster(int[] delays, int n) {
            if (n <= 0) return Double.NaN;
            int[] count = new int[Rules.WAIT + 2];
            for (int i = 0; i < n; i++) count[Math.max(1, Math.min(Rules.WAIT, delays[i]))]++;
            int at = 1;
            for (int d = 2; d <= Rules.WAIT; d++) {
                if (count[d] + count[d + 1] > count[at] + count[at + 1]) at = d;
            }
            return (at * count[at] + (at + 1.0) * count[at + 1]) / (count[at] + count[at + 1]);
        }

        /** Ticks of lag: the measure once there are hits enough, else the server's figure, else none. */
        static double ticks(double cluster, int n, int reportedMs) {
            if (n >= Rules.ENOUGH) return Math.max(0, cluster - Rules.LOCAL);
            return reportedMs > 0 ? reportedMs / Rules.TICK_MS : 0;
        }

        /** The band stays while the lag is within {@link Rules#HOLD} of it, and is else the nearest, the last one at most. */
        static int band(int now, double ticks) {
            if (Math.abs(ticks - now) <= Rules.HOLD) return now;
            return (int) Math.max(0, Math.min(Rules.TOP, Math.round(ticks)));
        }

        /** A flash this many ticks after the click is that click's. One on the click's own tick was on its way before it. */
        static boolean ours(int since) {
            return since >= 1 && since <= Rules.WAIT;
        }
    }

    private final int[] delays = new int[Rules.KEEP];
    private int n, next; // delays held, and where the next one goes
    private int foe, last, lastHurt, since = -1; // ticks since the click still waiting for its flash, -1 with none waiting
    private boolean seen;
    private int reported = -1, band;

    /** Another server, or none: what was measured was the last one's. */
    void reset() {
        n = next = band = 0;
        since = -1;
        reported = -1;
        seen = false;
    }

    /** The server's own figure for our round trip in milliseconds, negative when it gives none. */
    void reported(int ms) {
        reported = ms;
        band = Rule.band(band, ticks());
    }

    /**
     * One tick of the fight body.
     *
     * @param now     our tick count: a tick that does not follow the last one read is the start of another fight
     * @param foe     the target's entity id
     * @param clicked we sent an attack this tick
     * @param burning the foe is on fire: it flashes on the fire's own beat, and no click is matched to a flash
     */
    void tick(int now, int foe, int hurtTime, int hurtDuration, boolean clicked, boolean burning) {
        boolean same = seen && foe == this.foe && now == last + 1;
        boolean flash = same && hurtTime > lastHurt && hurtTime >= hurtDuration - 1;
        if (!same || burning) since = -1; // a click at the last foe, or in the last fight, says nothing about this one
        else if (since >= 0) since++;
        if (flash && since >= 0) {
            // a second click inside the wait leaves the first one's count running: the foe cannot be hurt again that soon
            if (Rule.ours(since)) {
                delays[next] = since;
                next = (next + 1) % Rules.KEEP;
                n = Math.min(n + 1, Rules.KEEP);
                band = Rule.band(band, ticks());
            }
            since = -1;
        }
        if (since > Rules.WAIT) since = -1; // blocked, or missed
        if (clicked && since < 0 && !burning) since = 0;
        seen = true;
        last = now;
        this.foe = foe;
        lastHurt = hurtTime;
    }

    /** Ticks of lag as far as known, 0 when nothing is. */
    double ticks() {
        return Rule.ticks(Rule.cluster(delays, n), n, reported);
    }

    /** Whole ticks of lag, steadied: what windows are kept apart by. */
    int band() {
        return band;
    }

    /** Hits the measure rests on; under {@link Rules#ENOUGH} it is the server's figure that is read. */
    int hits() {
        return n;
    }

    /** For the fight log. */
    String note() {
        return String.format(Locale.ROOT, "lag band=%d ticks=%.2f hits=%d reported=%d", band, ticks(), n, reported);
    }
}
