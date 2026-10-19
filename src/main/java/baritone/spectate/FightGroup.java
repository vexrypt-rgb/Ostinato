/*
 * Ostinato: new file (spectator fight types: 1v1, FFA, team). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: everyone tied together by hits while their fights are on, so a watched fight is known to be a 1v1, a
 * free-for-all or a team fight. Two players are in one group when one hit the other; a hit across two groups merges
 * them. The group closes when the last of its fights ends, and only then are its fights told what kind they were: a
 * fight that looked like a 1v1 until a third player stepped in is not one.
 * <p>
 * Teams are worked out from what the client can see of each player ({@link Clue}: the scoreboard team, the team's
 * and the name's colour, dyed armour, the kit) and from who hits whom: allies never trade hits, stand near each other
 * and hit the same enemies. Every hit is kept with attacker and victim, for later multi-opponent skills.
 */
public final class FightGroup {
    /** Blocks apart that count as standing together. */
    static final double NEAR = 7;
    /** Ticks that make one hit follow up on another (focus fire, peeling, third-partying). */
    static final int WINDOW = 40;
    /** The ally score two members need to be put in one team. */
    static final double MERGE = 3.5;

    /** What the client shows of one player that may tell their team. Values unknown are "" or -1. */
    public static final class Clue {
        public final String team;
        public final int teamColor, nameColor, dye;
        public final String kit;

        public Clue(String team, int teamColor, int nameColor, int dye, String kit) {
            this.team = team == null ? "" : team;
            this.teamColor = teamColor;
            this.nameColor = nameColor;
            this.dye = dye;
            this.kit = kit == null ? "" : kit;
        }

        public Clue withKit(String k) {
            return new Clue(team, teamColor, nameColor, dye, k);
        }

        static final Clue NONE = new Clue("", -1, -1, -1, "");
    }

    public final int id, start;
    int last;
    final Map<Integer, String> members = new LinkedHashMap<>();
    /** {tick, attacker, victim, crit 0/1}, in order. */
    public final List<int[]> hits = new ArrayList<>();
    private final Map<Long, int[]> pair = new HashMap<>(); // key -> {hits low->high, high->low}
    private final Map<Long, Integer> near = new HashMap<>(); // samples standing within NEAR
    int open; // fights of it still on
    final List<Object[]> done = new ArrayList<>(); // {Fight, winner, why, tick} waiting for the group to close
    final Map<Integer, KitSignatures.Result> kits = new HashMap<>(); // labels kept for members who died before the close
    final java.util.Set<Integer> dead = new java.util.HashSet<>();
    int samples;
    /** Once a second: tick, then {id, x, y, z, hp} of each member seen; for the group log. */
    public final List<Object[]> track = new ArrayList<>();
    // set on close
    public String type = "1v1", shape = "1v1";
    public List<List<Integer>> teams = new ArrayList<>();
    public final Map<Integer, Map<String, Integer>> skills = new LinkedHashMap<>();

    FightGroup(int id, int tick) {
        this.id = id;
        this.start = tick;
        this.last = tick;
    }

    static long key(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    void join(int idx, String name) {
        if (name != null) members.put(idx, name);
        else members.putIfAbsent(idx, "#" + idx);
    }

    void hit(int tick, int att, String an, int vic, String vn, boolean crit) {
        join(att, an);
        join(vic, vn);
        hits.add(new int[]{tick, att, vic, crit ? 1 : 0});
        pair.computeIfAbsent(key(att, vic), k -> new int[2])[att < vic ? 0 : 1]++;
        last = tick;
    }

    void absorb(FightGroup o) {
        for (Map.Entry<Integer, String> e : o.members.entrySet()) join(e.getKey(), e.getValue());
        hits.addAll(o.hits);
        hits.sort((x, y) -> Integer.compare(x[0], y[0]));
        for (Map.Entry<Long, int[]> e : o.pair.entrySet()) {
            int[] p = pair.computeIfAbsent(e.getKey(), k -> new int[2]);
            p[0] += e.getValue()[0];
            p[1] += e.getValue()[1];
        }
        for (Map.Entry<Long, Integer> e : o.near.entrySet()) near.merge(e.getKey(), e.getValue(), Integer::sum);
        open += o.open;
        done.addAll(o.done);
        kits.putAll(o.kits);
        dead.addAll(o.dead);
        samples += o.samples;
        track.addAll(o.track);
        track.sort((x, y) -> Integer.compare((Integer) x[0], (Integer) y[0]));
        last = Math.max(last, o.last);
    }

    /** Once a second: who stands near whom. */
    void sample(java.util.function.ToDoubleBiFunction<Integer, Integer> dist) {
        samples++;
        List<Integer> ids = new ArrayList<>(members.keySet());
        for (int i = 0; i < ids.size(); i++)
            for (int j = i + 1; j < ids.size(); j++) {
                double d = dist.applyAsDouble(ids.get(i), ids.get(j));
                if (!Double.isNaN(d) && d < NEAR) near.merge(key(ids.get(i), ids.get(j)), 1, Integer::sum);
            }
    }

    void track(int tick, Map<Integer, double[]> where) {
        if (track.size() > 6000) return; // five minutes of a long brawl is plenty
        List<double[]> rows = new ArrayList<>();
        for (int m : members.keySet()) {
            double[] w = where.get(m);
            if (w != null) rows.add(new double[]{m, w[0], w[1], w[2], w.length > 3 ? w[3] : Double.NaN});
        }
        if (!rows.isEmpty()) track.add(new Object[]{tick, rows});
    }

    int between(int a, int b) {
        int[] p = pair.get(key(a, b));
        return p == null ? 0 : p[0] + p[1];
    }

    int hitsBy(int a) {
        int n = 0;
        for (int[] h : hits) if (h[1] == a) n++;
        return n;
    }

    /**
     * How strongly two members look like allies: positive for allies, negative for foes. Hits between them settle it
     * against; the scoreboard team settles it either way; colours, dye and kit lean; standing together while both hit
     * others and never each other, and hitting the same enemies, lean towards.
     */
    double ally(int a, int b, Map<Integer, Clue> clues) {
        int h = between(a, b);
        if (h > 0) return -5.0 * h;
        Clue ca = clues.getOrDefault(a, Clue.NONE), cb = clues.getOrDefault(b, Clue.NONE);
        double s = 0;
        if (!ca.team.isEmpty() && !cb.team.isEmpty()) s += ca.team.equals(cb.team) ? 6 : -6;
        int ka = ca.teamColor >= 0 ? ca.teamColor : ca.nameColor, kb = cb.teamColor >= 0 ? cb.teamColor : cb.nameColor;
        if (ka >= 0 && kb >= 0) s += ka == kb ? 2 : -2;
        if (ca.dye >= 0 && cb.dye >= 0) s += ca.dye == cb.dye ? 2 : -1;
        if (!ca.kit.isEmpty() && !cb.kit.isEmpty() && !ca.kit.equals(KitSignatures.UNKNOWN) && !cb.kit.equals(KitSignatures.UNKNOWN))
            s += ca.kit.equals(cb.kit) ? 0.5 : -0.5;
        // both fought (hit someone), yet never each other
        int ha = hitsBy(a), hb = hitsBy(b);
        if (ha >= 2 && hb >= 2) {
            s += 1.5;
            // Ostinato: over the whole fight, the hits split everyone into two sides that only hit across, and these
            // two are on one side
            Integer sa = sides == null ? null : sides.get(a), sb = sides == null ? null : sides.get(b);
            if (sa != null && sa.equals(sb)) s += SIDE;
            int together = near.getOrDefault(key(a, b), 0);
            if (together >= 3 && together >= samples / 3) s += 1.5;
            // a common enemy: someone both of them hit
            for (int v : members.keySet())
                if (v != a && v != b && between(a, v) > 0 && between(b, v) > 0 && hitsOn(a, v) > 0 && hitsOn(b, v) > 0) {
                    s += 1;
                    break;
                }
        }
        return s;
    }

    private int hitsOn(int att, int vic) {
        int[] p = pair.get(key(att, vic));
        return p == null ? 0 : p[att < vic ? 0 : 1];
    }

    /** Ostinato: ally score for two on one side of the whole fight's hits (see {@link #foeSides()}). */
    static final double SIDE = 2;
    private Map<Integer, Integer> sides; // Ostinato: set while partitioning

    /**
     * Ostinato: who never hits whom over the whole fight. Colours members so that every pair that traded hits is on
     * two sides; returns the sides only when that works (no one hit someone on their own side), everyone is reached,
     * everyone hit at least twice, each side has two or more, and more than three quarters of the pairs across the
     * sides traded hits (so a chain of separate duels does not pass for two teams). Otherwise null.
     */
    Map<Integer, Integer> foeSides() {
        List<Integer> ids = new ArrayList<>(members.keySet());
        if (ids.size() < 4) return null;
        for (int m : ids) if (hitsBy(m) < 2) return null;
        Map<Integer, Integer> side = new HashMap<>();
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        side.put(ids.get(0), 0);
        q.add(ids.get(0));
        while (!q.isEmpty()) {
            int x = q.poll();
            for (int y : ids) {
                if (y == x || between(x, y) == 0) continue;
                Integer sy = side.get(y);
                if (sy == null) {
                    side.put(y, 1 - side.get(x));
                    q.add(y);
                } else if (sy.equals(side.get(x))) return null;
            }
        }
        if (side.size() != ids.size()) return null;
        int n0 = 0;
        for (int v : side.values()) if (v == 0) n0++;
        int n1 = ids.size() - n0;
        if (n0 < 2 || n1 < 2) return null;
        int across = 0;
        for (int x : ids) for (int y : ids) if (x < y && !side.get(x).equals(side.get(y)) && between(x, y) > 0) across++;
        return across > 0.75 * n0 * n1 ? side : null;
    }

    /** Puts members in teams: greedy merging of the strongest ally pairs, never across two members who traded hits. */
    static List<List<Integer>> partition(FightGroup g, Map<Integer, Clue> clues) {
        g.sides = g.foeSides(); // Ostinato
        List<Integer> ids = new ArrayList<>(g.members.keySet());
        Map<Integer, Integer> team = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) team.put(ids.get(i), i);
        List<double[]> pairs = new ArrayList<>();
        Map<Long, Double> score = new HashMap<>();
        for (int i = 0; i < ids.size(); i++)
            for (int j = i + 1; j < ids.size(); j++) {
                double s = g.ally(ids.get(i), ids.get(j), clues);
                score.put(key(ids.get(i), ids.get(j)), s);
                pairs.add(new double[]{s, ids.get(i), ids.get(j)});
            }
        pairs.sort((x, y) -> Double.compare(y[0], x[0]));
        // an ally pair needs more than not hitting each other and a common enemy (a third party looks like that too):
        // standing together as well, or a colour, dye or scoreboard team in common
        for (double[] p : pairs) {
            if (p[0] < MERGE) break;
            int ta = team.get((int) p[1]), tb = team.get((int) p[2]);
            if (ta == tb) continue;
            // the merged team must hold no foes
            boolean ok = true;
            double sum = 0;
            int n = 0;
            for (int x : ids)
                for (int y : ids)
                    if (x < y && (team.get(x) == ta && team.get(y) == tb || team.get(x) == tb && team.get(y) == ta)) {
                        double s = score.get(key(x, y));
                        if (s < 0) ok = false;
                        sum += s;
                        n++;
                    }
            if (!ok || sum / n < MERGE / 2) continue;
            for (int x : ids) if (team.get(x) == tb) team.put(x, ta);
        }
        Map<Integer, List<Integer>> by = new LinkedHashMap<>();
        for (int x : ids) by.computeIfAbsent(team.get(x), k -> new ArrayList<>()).add(x);
        List<List<Integer>> out = new ArrayList<>(by.values());
        out.sort((x, y) -> Integer.compare(y.size(), x.size()));
        return out;
    }

    /** Decides the kind of fight and the teams, and counts each member's multi-opponent moves. */
    void close(Map<Integer, Clue> clues) {
        if (members.size() <= 2) {
            type = "1v1";
            shape = "1v1";
            teams = new ArrayList<>();
            for (int m : members.keySet()) teams.add(java.util.Collections.singletonList(m)); // Ostinato: Java 8
        } else {
            teams = partition(this, clues);
            boolean anyTeam = false;
            for (List<Integer> t : teams) anyTeam |= t.size() > 1;
            type = anyTeam ? "team" : "ffa";
            StringBuilder sb = new StringBuilder();
            if (anyTeam) {
                for (List<Integer> t : teams) sb.append(sb.length() > 0 ? "v" : "").append(t.size());
            } else {
                sb.append("ffa").append(members.size());
            }
            shape = sb.toString();
        }
        skills.clear();
        Map<Integer, Integer> teamOf = new HashMap<>();
        for (int i = 0; i < teams.size(); i++) for (int m : teams.get(i)) teamOf.put(m, i);
        Map<Integer, Integer> lastVictim = new HashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            int[] h = hits.get(i);
            int t = h[0], att = h[1], vic = h[2];
            Map<String, Integer> sk = skills.computeIfAbsent(att, k -> new LinkedHashMap<>());
            sk.merge("hits", 1, Integer::sum);
            Integer lv = lastVictim.put(att, vic);
            if (lv != null && lv != vic) sk.merge("switches", 1, Integer::sum);
            for (int j = i - 1; j >= 0 && t - hits.get(j)[0] <= WINDOW; j--) {
                int[] o = hits.get(j);
                int oa = o[1], ov = o[2];
                boolean allyOfAtt = oa != att && teamOf.get(oa).equals(teamOf.get(att));
                // focus fire: an ally hit the same victim just before
                if (allyOfAtt && ov == vic) {
                    sk.merge("focus", 1, Integer::sum);
                    break;
                }
            }
            for (int j = i - 1; j >= 0 && t - hits.get(j)[0] <= WINDOW; j--) {
                int[] o = hits.get(j);
                // peeling: we hit someone who just hit our ally
                if (o[1] == vic && o[2] != att && teamOf.get(o[2]).equals(teamOf.get(att))) {
                    sk.merge("peel", 1, Integer::sum);
                    break;
                }
            }
            for (int j = i - 1; j >= 0 && t - hits.get(j)[0] <= WINDOW; j--) {
                int[] o = hits.get(j);
                // third-partying: the victim was trading with someone else, not of our team
                boolean other = (o[1] == vic && o[2] != att || o[2] == vic && o[1] != att);
                int third = o[1] == vic ? o[2] : o[1];
                if (other && !teamOf.get(third).equals(teamOf.get(att))) {
                    sk.merge("thirdParty", 1, Integer::sum);
                    skills.computeIfAbsent(vic, k -> new LinkedHashMap<>()).merge("thirdPartied", 1, Integer::sum);
                    break;
                }
            }
        }
    }

    public String names(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i : ids) sb.append(sb.length() > 0 ? "," : "").append(members.getOrDefault(i, "#" + i));
        return sb.toString();
    }

    /** {@code red1,red2|blue1,blue2}. */
    public String teamsLine() {
        StringBuilder sb = new StringBuilder();
        for (List<Integer> t : teams) sb.append(sb.length() > 0 ? "|" : "").append(names(t));
        return sb.toString();
    }

    public String skillsLine() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Map<String, Integer>> e : skills.entrySet()) {
            sb.append(sb.length() > 0 ? " " : "").append(members.getOrDefault(e.getKey(), "#" + e.getKey())).append(':');
            StringBuilder in = new StringBuilder();
            for (Map.Entry<String, Integer> s : e.getValue().entrySet()) in.append(in.length() > 0 ? "," : "").append(s.getKey()).append('=').append(s.getValue());
            sb.append(in);
        }
        return sb.toString();
    }

    public String describe() {
        return String.format(Locale.ROOT, "%s %s (%d players, %d hits): %s", type, shape, members.size(), hits.size(), teamsLine());
    }
}
