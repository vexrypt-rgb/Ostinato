/*
 * Ostinato: new file (spectator learning). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Ostinato: which watched players are fighting each other, and what the winners of finished fights did differently
 * from the losers. A fight starts with the first hit between two players and ends when one dies (the other wins),
 * when nothing passes between them for {@link #QUIET} ticks, or when they part beyond {@link #APART} blocks; a fight
 * that peters out is won by whoever is estimated healthier by more than two points, otherwise it is a draw and
 * teaches nothing. Each side has its own {@link Habits} for the fight. The book keeps Welford sums per trait for
 * winners and for losers: means, spread and an effect size, never the fights themselves.
 */
public final class FightBook {
    public static final int QUIET = 300;
    public static final double APART = 24;
    /** Fights shorter than this (one stray hit) are thrown away. */
    public static final int MIN_TICKS = 60, MIN_HITS = 3;

    public static final class Fight {
        public final int a, b; // entity ids, a < b
        public final String nameA, nameB;
        public final int start;
        int lastExchange;
        public final Habits ha = new Habits(), hb = new Habits();
        int hitsA, hitsB;

        Fight(int a, String nameA, int b, String nameB, int tick) {
            this.a = a;
            this.b = b;
            this.nameA = nameA;
            this.nameB = nameB;
            this.start = tick;
            this.lastExchange = tick;
        }

        public Habits of(int id) {
            return id == a ? ha : id == b ? hb : null;
        }

        public int other(int id) {
            return id == a ? b : a;
        }
    }

    /** Welford running mean and variance. */
    static final class Stat {
        int n;
        double mean, m2;

        void add(double v) {
            if (Double.isNaN(v)) return;
            n++;
            double d = v - mean;
            mean += d / n;
            m2 += d * (v - mean);
        }

        double var() {
            return n > 1 ? m2 / (n - 1) : Double.NaN;
        }
    }

    private final Map<Long, Fight> active = new HashMap<>();
    private final Map<String, Stat> winners = new LinkedHashMap<>(), losers = new LinkedHashMap<>();
    private final Consumer<Fight> onStart;
    private final FightEnd onEnd;
    int finished, draws, discarded;

    public interface FightEnd {
        /** {@code winner} is an entity id, or -1 for a draw. */
        void ended(Fight f, int winner, String why);
    }

    public FightBook(Consumer<Fight> onStart, FightEnd onEnd) {
        this.onStart = onStart;
        this.onEnd = onEnd;
    }

    private static long key(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    /** {@code attacker} hit {@code victim}; starts their fight if none is on. */
    public Fight hit(int tick, int attacker, String attackerName, int victim, String victimName) {
        long k = key(attacker, victim);
        Fight f = active.get(k);
        if (f == null) {
            f = attacker < victim ? new Fight(attacker, attackerName, victim, victimName, tick) : new Fight(victim, victimName, attacker, attackerName, tick);
            active.put(k, f);
            if (onStart != null) onStart.accept(f);
        }
        f.lastExchange = tick;
        if (attacker == f.a) f.hitsA++;
        else f.hitsB++;
        return f;
    }

    /** Every fight this player is in. */
    public List<Fight> fightsOf(int id) {
        List<Fight> out = new ArrayList<>(2);
        for (Fight f : active.values()) if (f.a == id || f.b == id) out.add(f);
        return out;
    }

    public java.util.Collection<Fight> active() {
        return active.values();
    }

    /** This player died: every fight of theirs is lost. */
    public void died(int tick, int id) {
        for (Fight f : fightsOf(id)) finish(f, f.other(id), "death", tick);
    }

    /**
     * Once a tick: ends fights gone quiet or apart. {@code dist} gives the distance between two ids (NaN when one is
     * gone), {@code hp} the estimated health of one (NaN unknown).
     */
    public void tick(int tick, java.util.function.ToDoubleBiFunction<Integer, Integer> dist, java.util.function.ToDoubleFunction<Integer> hp) {
        for (Fight f : new ArrayList<>(active.values())) {
            double d = dist.applyAsDouble(f.a, f.b);
            boolean quiet = tick - f.lastExchange > QUIET;
            if (!quiet && !(Double.isNaN(d) || d > APART)) continue;
            double ha = hp.applyAsDouble(f.a), hb = hp.applyAsDouble(f.b);
            int winner = Double.isNaN(ha) || Double.isNaN(hb) || Math.abs(ha - hb) <= 2 ? -1 : ha > hb ? f.a : f.b;
            finish(f, winner, quiet ? "quiet" : "apart", tick);
        }
    }

    private void finish(Fight f, int winner, String why, int tick) {
        active.remove(key(f.a, f.b));
        if (tick - f.start < MIN_TICKS || f.hitsA + f.hitsB < MIN_HITS) {
            discarded++;
            return;
        }
        if (winner < 0) draws++;
        else {
            finished++;
            Habits w = f.of(winner), l = f.of(f.other(winner));
            add(winners, w.traits());
            add(losers, l.traits());
        }
        if (onEnd != null) onEnd.ended(f, winner, why);
    }

    private static void add(Map<String, Stat> book, Map<String, Double> traits) {
        for (Map.Entry<String, Double> e : traits.entrySet()) book.computeIfAbsent(e.getKey(), k -> new Stat()).add(e.getValue());
    }

    /** Winners' mean of one trait, and how many winners had it. */
    public double winnerMean(String trait) {
        Stat s = winners.get(trait);
        return s == null || s.n == 0 ? Double.NaN : s.mean;
    }

    public int winnerCount(String trait) {
        Stat s = winners.get(trait);
        return s == null ? 0 : s.n;
    }

    /** Lines of "trait: winners mean (n) vs losers mean (n), effect d", strongest effect first. */
    public List<String> report() {
        List<String[]> rows = new ArrayList<>();
        for (String t : winners.keySet()) {
            Stat w = winners.get(t), l = losers.get(t);
            if (w == null || l == null || w.n < 2 || l.n < 2) continue;
            double sd = Math.sqrt((w.var() * (w.n - 1) + l.var() * (l.n - 1)) / Math.max(1, w.n + l.n - 2));
            double eff = sd > 1e-9 ? (w.mean - l.mean) / sd : 0;
            rows.add(new String[]{String.format(Locale.ROOT, "%08.3f", 1000 - Math.abs(eff)),
                    String.format(Locale.ROOT, "%s: winners %.2f (n=%d) vs losers %.2f (n=%d), effect %+.2f", t, w.mean, w.n, l.mean, l.n, eff)});
        }
        rows.sort((x, y) -> x[0].compareTo(y[0]));
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "spectated fights: %d decided, %d draws, %d too short; %d on now", finished, draws, discarded, active.size()));
        for (String[] r : rows) out.add(r[1]);
        return out;
    }

    public int decided() {
        return finished;
    }
}
