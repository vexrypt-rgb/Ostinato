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
        /** Ostinato: unique for the book's life (the same two players may fight again). */
        public final int serial = ++serials;
        public final Habits ha = new Habits(), hb = new Habits();
        int hitsA, hitsB;
        // Ostinato: kit detection and fight type, set when the fight's group closes
        public FightGroup group;
        public KitSignatures.Result kitA, kitB;

        /** {@code 1v1}, {@code ffa} or {@code team}; 1v1 until the group closes. */
        public String type() {
            return group == null ? "1v1" : group.type;
        }

        public KitSignatures.Result kitOf(int id) {
            return id == a ? kitA : id == b ? kitB : null;
        }

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
    // Ostinato: the same, per kit of the fighter (1v1 fights only)
    private final Map<String, Map<String, Stat>> winnersByKit = new LinkedHashMap<>(), losersByKit = new LinkedHashMap<>();
    private final Consumer<Fight> onStart;
    private final FightEnd onEnd;
    int finished, draws, discarded;
    // Ostinato: fight groups (1v1 / FFA / team), kit evidence per player, what the client shows of their team
    private final Map<Integer, FightGroup> groupOf = new HashMap<>();
    private final Map<Integer, KitEvidence> kits = new HashMap<>();
    private final Map<Integer, FightGroup.Clue> clues = new HashMap<>();
    private final Map<String, Integer> multi = new LinkedHashMap<>(); // fights kept out of 1v1 tuning, by type
    private final Map<String, Integer> multiSkills = new LinkedHashMap<>(); // their move counts summed, type.skill
    private int groups;
    private static int serials;
    private java.util.function.Consumer<Fight> onPairEnd;
    private java.util.function.BiConsumer<Fight, Boolean> onSettled;
    private boolean multiApart = true;
    private java.util.function.Consumer<FightGroup> onGroup;
    private KitSignatures signatures;
    private LearnedKits learned; // null: the game folder's
    private boolean learnKits = true;
    /** A label below this confidence counts as unknown for the per-kit tallies. */
    public static final double KIT_SURE = 0.45;

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
        return hit(tick, attacker, attackerName, victim, victimName, false);
    }

    /** Ostinato: {@code crit} kept on the group's hit list. */
    public Fight hit(int tick, int attacker, String attackerName, int victim, String victimName, boolean crit) {
        long k = key(attacker, victim);
        Fight f = active.get(k);
        // Ostinato: the hit ties both into one group, merging two groups it crosses
        FightGroup ga = groupOf.get(attacker), gv = groupOf.get(victim), g;
        if (ga == null && gv == null) g = new FightGroup(++groups, tick);
        else if (ga == null || gv == null || ga == gv) g = ga != null ? ga : gv;
        else {
            g = ga.members.size() >= gv.members.size() ? ga : gv;
            FightGroup o = g == ga ? gv : ga;
            g.absorb(o);
            for (Fight x : active.values()) if (x.group == o) x.group = g;
            for (Object[] d : g.done) ((Fight) d[0]).group = g;
            for (Map.Entry<Integer, FightGroup> e : groupOf.entrySet()) if (e.getValue() == o) e.setValue(g);
        }
        groupOf.put(attacker, g);
        groupOf.put(victim, g);
        g.hit(tick, attacker, attackerName, victim, victimName, crit);
        if (f == null) {
            f = attacker < victim ? new Fight(attacker, attackerName, victim, victimName, tick) : new Fight(victim, victimName, attacker, attackerName, tick);
            active.put(k, f);
            f.group = g;
            g.open++;
            if (onStart != null) onStart.accept(f);
        }
        f.lastExchange = tick;
        if (attacker == f.a) f.hitsA++;
        else f.hitsB++;
        return f;
    }

    // ---------------------------------------------------------------- Ostinato: kit detection

    /** What this player wears and holds from {@code tick} on (see {@link KitEvidence#gear}). */
    public void gear(int tick, int id, Map<String, Double> tokens) {
        kits.computeIfAbsent(id, k -> new KitEvidence()).gear(tick, tokens);
    }

    /** Something they did that tells of their kit. */
    public void kitEvent(int tick, int id, String token) {
        kits.computeIfAbsent(id, k -> new KitEvidence()).event(tick, token);
    }

    /** What the client shows of their team (scoreboard team, colours, dye). */
    public void clue(int id, FightGroup.Clue c) {
        clues.put(id, c);
    }

    /** Forgets a player's kit evidence (they died, left or changed world: the next kit may differ). */
    public void forgetKit(int id) {
        kits.remove(id);
    }

    public void onGroup(java.util.function.Consumer<FightGroup> l) {
        onGroup = l;
    }

    /** Called as soon as a fight between two players is over, before its group has closed. */
    public void onPairEnd(java.util.function.Consumer<Fight> l) {
        onPairEnd = l;
    }

    /** Called for every fight once its group has closed, {@code true} if it was long enough to count. */
    public void onSettled(java.util.function.BiConsumer<Fight, Boolean> l) {
        onSettled = l;
    }

    /** Whether FFA and team fights are kept out of the 1v1 tallies ({@code spectateFightTypes}); on by default. */
    public void multiApart(boolean on) {
        multiApart = on;
    }

    /** Forgets everyone's kit and team clues (another world: entity ids start over). */
    public void resetPlayers() {
        kits.clear();
        clues.clear();
    }

    /** Once a second: where each player in an open group is and their health ({x, y, z, hp}), for the group log. */
    public void track(int tick, Map<Integer, double[]> where) {
        for (FightGroup g : new java.util.HashSet<>(groupOf.values())) g.track(tick, where);
    }

    public void signatures(KitSignatures k) {
        signatures = k;
    }

    /** Whether loadouts no kit fits become learned kits ({@code spectateLearnKits}). */
    public void learnKits(boolean on) {
        learnKits = on;
    }

    public void learned(LearnedKits l) {
        learned = l;
    }

    private LearnedKits learnedKits() {
        return learned != null ? learned : LearnedKits.get();
    }

    private KitSignatures sig() {
        return signatures != null ? signatures : KitSignatures.get();
    }

    /** This player's kit as seen so far. */
    public KitSignatures.Result kit(int tick, int id) {
        return kit(tick, id, false);
    }

    /**
     * This player's kit as seen so far. One no built-in kit fits is looked up among the learned kits; with
     * {@code learn} (once a fighter, when their group closes or they die) it may join one or become a new one.
     */
    KitSignatures.Result kit(int tick, int id, boolean learn) {
        KitEvidence ev = kits.get(id);
        if (ev == null) return new KitSignatures.Result(KitSignatures.UNKNOWN, 0, KitSignatures.UNKNOWN, 0);
        KitSignatures.Result r = sig().classify(ev, tick);
        if (!learnKits || !r.label.equals(KitSignatures.UNKNOWN)) return r; // a known kit, even an unsure one, is not new
        double seen = ev.observed(tick);
        Map<String, Double> f = ev.features(tick);
        Object[] m = learnedKits().match(f);
        if (m == null && learn && seen >= LearnedKits.MIN_OBSERVED) {
            LearnedKits.Kit k = learnedKits().learn(f);
            if (k != null) return new KitSignatures.Result(k.label, Math.min(1, seen / KitSignatures.FULL_TICKS) * 0.9, k.label, 0);
        }
        if (m == null) return r;
        LearnedKits.Kit k = (LearnedKits.Kit) m[0];
        if (learn && seen >= LearnedKits.MIN_OBSERVED) learnedKits().learn(f); // seen again: the kit's mean moves
        return new KitSignatures.Result(k.label, (Double) m[1] * Math.min(1, seen / KitSignatures.FULL_TICKS), k.label, 0);
    }

    public KitEvidence evidence(int id) {
        return kits.get(id);
    }

    /** The group a player is fighting in, if any. */
    public FightGroup groupOf(int id) {
        return groupOf.get(id);
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
        // Ostinato: their kit is read before the evidence goes (they may come back with another)
        FightGroup g = groupOf.get(id);
        if (g != null) {
            g.kits.put(id, kit(tick, id, true));
            g.dead.add(id);
        }
        for (Fight f : fightsOf(id)) finish(f, f.other(id), "death", tick);
        forgetKit(id);
    }

    /**
     * Once a tick: ends fights gone quiet or apart. {@code dist} gives the distance between two ids (NaN when one is
     * gone), {@code hp} the estimated health of one (NaN unknown).
     */
    public void tick(int tick, java.util.function.ToDoubleBiFunction<Integer, Integer> dist, java.util.function.ToDoubleFunction<Integer> hp) {
        // Ostinato: who stands near whom in each open group, for the teams
        for (FightGroup g : new java.util.HashSet<>(groupOf.values())) g.sample(dist);
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
        if (onPairEnd != null) onPairEnd.accept(f);
        // Ostinato: kits are read now; the fight waits for its group to close to learn whether it was a 1v1
        f.kitA = f.group != null && f.group.kits.containsKey(f.a) ? f.group.kits.get(f.a) : kit(tick, f.a);
        f.kitB = f.group != null && f.group.kits.containsKey(f.b) ? f.group.kits.get(f.b) : kit(tick, f.b);
        FightGroup g = f.group;
        if (g == null) {
            settle(f, winner, why, tick);
            return;
        }
        g.done.add(new Object[]{f, winner, why, tick});
        g.open--;
        if (g.open <= 0) closeGroup(g, tick);
    }

    /** Ostinato: the group's last fight is over: what kind of fight it was, then each of its fights is counted. */
    private void closeGroup(FightGroup g, int tick) {
        Map<Integer, FightGroup.Clue> cl = new HashMap<>();
        for (int m : g.members.keySet()) {
            KitSignatures.Result k = g.kits.containsKey(m) ? g.kits.get(m) : kit(tick, m, true);
            g.kits.put(m, k);
            FightGroup.Clue c = clues.getOrDefault(m, FightGroup.Clue.NONE);
            cl.put(m, c.withKit(k.confidence >= KIT_SURE ? k.label : ""));
        }
        g.close(cl);
        for (Map.Entry<Integer, FightGroup> e : new ArrayList<>(groupOf.entrySet())) if (e.getValue() == g) groupOf.remove(e.getKey());
        if (!g.type.equals("1v1")) {
            multi.merge(g.type, 1, Integer::sum);
            for (Map<String, Integer> sk : g.skills.values()) for (Map.Entry<String, Integer> e : sk.entrySet()) multiSkills.merge(g.type + "." + e.getKey(), e.getValue(), Integer::sum);
        }
        for (Object[] d : g.done) {
            Fight f = (Fight) d[0];
            // the group's labels, which may have learned a kit since the fight ended
            if (g.kits.containsKey(f.a)) f.kitA = g.kits.get(f.a);
            if (g.kits.containsKey(f.b)) f.kitB = g.kits.get(f.b);
            settle(f, (Integer) d[1], (String) d[2], (Integer) d[3]);
        }
        g.done.clear();
        if (onGroup != null) onGroup.accept(g);
    }

    private void settle(Fight f, int winner, String why, int tick) {
        boolean counts = tick - f.start >= MIN_TICKS && f.hitsA + f.hitsB >= MIN_HITS;
        if (onSettled != null) onSettled.accept(f, counts);
        if (!counts) {
            discarded++;
            return;
        }
        // Ostinato: FFA and team fights stay out of the 1v1 tallies (kept in the group for multi-opponent skills)
        if (multiApart && !f.type().equals("1v1")) {
            if (onEnd != null) onEnd.ended(f, winner, why);
            return;
        }
        if (winner < 0) draws++;
        else {
            finished++;
            Habits w = f.of(winner), l = f.of(f.other(winner));
            add(winners, w.traits());
            add(losers, l.traits());
            // Ostinato: and per kit, under each side's own kit
            add(winnersByKit.computeIfAbsent(kitKey(f.kitOf(winner)), k -> new LinkedHashMap<>()), w.traits());
            add(losersByKit.computeIfAbsent(kitKey(f.kitOf(f.other(winner))), k -> new LinkedHashMap<>()), l.traits());
        }
        if (onEnd != null) onEnd.ended(f, winner, why);
    }

    /** Ostinato: the kit a fighter's numbers are kept under: the label, or unknown below {@link #KIT_SURE}. */
    static String kitKey(KitSignatures.Result r) {
        return r == null || r.confidence < KIT_SURE ? KitSignatures.UNKNOWN : r.label;
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

    /** Ostinato: winners' mean of one trait among winners of one kit (1v1 only). */
    public double winnerMean(String kit, String trait) {
        Map<String, Stat> m = winnersByKit.get(kit);
        Stat s = m == null ? null : m.get(trait);
        return s == null || s.n == 0 ? Double.NaN : s.mean;
    }

    public int winnerCount(String kit, String trait) {
        Map<String, Stat> m = winnersByKit.get(kit);
        Stat s = m == null ? null : m.get(trait);
        return s == null ? 0 : s.n;
    }

    /** Ostinato: the kits winners have been counted under. */
    public java.util.Set<String> kitsSeen() {
        return winnersByKit.keySet();
    }

    /** Ostinato: fights kept out of 1v1 tuning, by type. */
    public Map<String, Integer> multi() {
        return multi;
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
        // Ostinato: per kit, and the fights kept apart
        StringBuilder kb = new StringBuilder();
        for (Map.Entry<String, Map<String, Stat>> e : winnersByKit.entrySet()) {
            Stat s = e.getValue().get("cps");
            kb.append(kb.length() > 0 ? ", " : "").append(e.getKey()).append(' ').append(s == null ? 0 : s.n);
        }
        if (kb.length() > 0) out.add("1v1 winners by kit: " + kb);
        if (!multi.isEmpty()) out.add("kept out of 1v1 tuning: " + multi + " moves " + multiSkills);
        for (String[] r : rows) out.add(r[1]);
        return out;
    }

    public int decided() {
        return finished;
    }
}
