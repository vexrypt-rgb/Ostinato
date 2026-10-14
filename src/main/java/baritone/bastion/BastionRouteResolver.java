package baritone.bastion;

import baritone.bastion.BastionPlan.Kind;
import baritone.bastion.BastionPlan.Point;
import baritone.bastion.BastionRoutes.Step;

import java.util.*;

/**
 * Resolves the objective-level route (BastionRoutes) to the scanned chests and gold blocks. Each step that targets
 * blocks gets a selector over the points still unclaimed; steps that are movement tactics (pillar, pocket, bridge a
 * gap, pit) resolve to nothing here and are handled by the process's own behaviours (trap/pit, distract, shelter).
 * Points no step claims are appended at the end in nearest-first order so nothing scanned is lost.
 * Pure logic: points, the bastion centre and our start.
 */
public final class BastionRouteResolver {
    private BastionRouteResolver() {}

    /** Treasure: the central room (core) holds the good chests and gold. */
    public static final int TREASURE_CORE = 18;
    /** A gold cluster: blocks within this distance of each other. */
    public static final int CLUSTER = 4;

    public record Target(Step step, Point point) {}

    public static List<Target> resolve(String layout, List<Step> route, int cx, int cy, int cz, int sx, int sy, int sz, List<Point> points) {
        List<Point> left = new ArrayList<>(points);
        List<Target> out = new ArrayList<>();
        int[] at = {sx, sy, sz};
        for (Step s : route) {
            List<Point> picked = select(layout, s, cx, cy, cz, at, left);
            if (picked.isEmpty()) continue;
            List<Point> ordered = BastionPlan.order(layout, cx, cy, cz, at[0], at[1], at[2], picked);
            for (Point p : ordered) { out.add(new Target(s, p)); left.remove(p); }
            Point last = ordered.get(ordered.size() - 1);
            at[0] = last.x(); at[1] = last.y(); at[2] = last.z();
        }
        if (!left.isEmpty()) for (Point p : BastionPlan.order(layout, cx, cy, cz, at[0], at[1], at[2], left)) out.add(new Target(null, p));
        return out;
    }

    /** Which points a step claims from those left. Empty for tactic steps. */
    static List<Point> select(String layout, Step s, int cx, int cy, int cz, int[] at, List<Point> left) {
        List<Point> gold = of(left, Kind.GOLD), chests = of(left, Kind.CHEST);
        switch (s) {
            // ---- treasure
            case MINE_HIGH_GOLD_CRAFT_INGOTS: return highest(gold, 1);
            case MINE_GOLD_SIDE_1: case MINE_GOLD_SIDE_2: {
                List<Point> core = within(gold, cx, cz, TREASURE_CORE);
                int axis = spreadAxis(core);
                int startSide = Integer.signum((axis == 0 ? at[0] - cx : at[2] - cz));
                if (startSide == 0) startSide = 1;
                int want = s == Step.MINE_GOLD_SIDE_1 ? startSide : -startSide;
                List<Point> r = new ArrayList<>();
                for (Point p : core) if (Integer.signum(axis == 0 ? p.x() - cx : p.z() - cz) == want) r.add(p);
                // the pit corner gold sits higher than the backup gold below it: take the upper 6 per side
                r.sort(Comparator.comparingInt(Point::y).reversed());
                return r.subList(0, Math.min(6, r.size()));
            }
            case LOOT_DOUBLE: return paired(within(chests, cx, cz, TREASURE_CORE), true);
            case LOOT_SINGLES: return paired(within(chests, cx, cz, TREASURE_CORE), false);
            case BACKUP_GOLD_1: return nearest(within(gold, cx, cz, TREASURE_CORE), at, 1);
            case BACKUP_GOLD_2: return nearest(within(gold, cx, cz, TREASURE_CORE), at, 1);
            // ---- bridge: the whole chalice (largest gold cluster), then the rampart chests
            case MINE_CHALICE: return largestCluster(gold);
            case LOOT_RAMPARTS: return chests;
            // ---- stables
            case GOOD_GAP_GOLD: { List<Point> c = largestCluster(gold); return c.size() >= 2 && lowest(c) <= cy ? c : List.of(); }
            case TRIPLE_GOLD: { List<Point> c = largestCluster(gold); return c.size() >= 4 ? c : List.of(); }
            case TRIPLE_CHESTS: return highest(chests, 3);
            case MIDDLE_BOTTOM_CHEST: { List<Point> c = new ArrayList<>(chests); c.sort(Comparator.comparingInt(Point::y).thenComparingDouble(p -> p.horiz(cx, cz))); return c.isEmpty() ? c : c.subList(0, 1); }
            case UPPER_CHESTS: { List<Point> r = new ArrayList<>(); for (Point p : chests) if (p.y() > cy) r.add(p); return r; }
            // ---- housing: picks follow the game's bastion/units templates (26.3 jar):
            //   ramparts_0 (tall rampart): double + single side by side at mid height = the top triple
            //   wall_base: a double chest low in the wall next to 9 low gold blocks (the strip-tunnel wall gold) + 3 high
            //   connected_wall: 4 low gold + 1 halfway up (backup gold); center_pieces: one single among soul sand
            case LOOT_TOP_LARGE: case LOOT_ARMOR_CHEST: { List<Point> t = topTriple(chests); return t.size() == 3 ? new ArrayList<>(t.subList(0, 2)) : highest(paired(chests, true), 2); }
            case LOOT_TOP_SINGLE: { List<Point> t = topTriple(chests); return t.size() == 3 ? new ArrayList<>(t.subList(2, 3)) : highest(paired(chests, false), 1); }
            case LOOT_TOP_TRIPLE: { List<Point> t = topTriple(chests); return t.isEmpty() ? highest(chests, 3) : t; }
            case LOOT_LEFT_RAMPART_TRIPLE: return topTriple(chests);
            case HOUSING_BOTTOM_CHEST: { List<Point> d = paired(chests, true); d.sort(Comparator.comparingInt(Point::y)); return d.isEmpty() ? lowestOne(chests) : pairOf(d.get(0), d); }
            case CORNER_CHEST: return nearest(chests, at, 1);
            case MINE_WALL_GOLD: case GOLD_ELEVATOR: return largestCluster(lowGold(gold));
            case T_TUNNEL_GOLD: return lowGold(gold);
            case HOUSING_BACKUP_GOLD: return gold;
            case OUTSIDE_CHESTS: { List<Point> o = new ArrayList<>(); for (Point p : chests) if (p.horiz(cx, cz) > TREASURE_CORE) o.add(p); return o; }
            default: return List.of();
        }
    }

    /** True when this step breaks gold blocks (the distract-first gate applies). */
    public static boolean breaksGold(Step s) {
        return BastionRoutes.needsDistractFirst(s, 0);
    }

    // ---- helpers
    static List<Point> of(List<Point> ps, Kind k) {
        List<Point> r = new ArrayList<>();
        for (Point p : ps) if (p.kind() == k) r.add(p);
        return r;
    }

    static List<Point> within(List<Point> ps, int cx, int cz, int r) {
        List<Point> o = new ArrayList<>();
        for (Point p : ps) if (p.horiz(cx, cz) <= r) o.add(p);
        return o;
    }

    static List<Point> highest(List<Point> ps, int n) {
        List<Point> o = new ArrayList<>(ps);
        o.sort(Comparator.comparingInt(Point::y).reversed());
        return new ArrayList<>(o.subList(0, Math.min(n, o.size())));
    }

    static int lowest(List<Point> ps) {
        int m = Integer.MAX_VALUE;
        for (Point p : ps) m = Math.min(m, p.y());
        return m;
    }

    static List<Point> nearest(List<Point> ps, int[] at, int n) {
        List<Point> o = new ArrayList<>(ps);
        o.sort(Comparator.comparingDouble(p -> Math.pow(p.x() - at[0], 2) + Math.pow(p.y() - at[1], 2) + Math.pow(p.z() - at[2], 2)));
        return new ArrayList<>(o.subList(0, Math.min(n, o.size())));
    }

    /** Chests with (paired=true) or without a horizontally adjacent chest at the same height. */
    static List<Point> paired(List<Point> chests, boolean paired) {
        List<Point> o = new ArrayList<>();
        for (Point p : chests) {
            boolean has = false;
            for (Point q : chests) if (q != p && q.y() == p.y() && Math.abs(q.x() - p.x()) + Math.abs(q.z() - p.z()) == 1) { has = true; break; }
            if (has == paired) o.add(p);
        }
        return o;
    }

    /** Gold within one block of the lowest gold: the floor-level wall gold of wall_base / connected_wall. */
    static List<Point> lowGold(List<Point> gold) {
        int m = lowest(gold);
        List<Point> o = new ArrayList<>();
        for (Point p : gold) if (p.y() <= m + 1) o.add(p);
        return o;
    }

    static List<Point> lowestOne(List<Point> ps) {
        List<Point> o = new ArrayList<>(ps);
        o.sort(Comparator.comparingInt(Point::y));
        return o.isEmpty() ? o : new ArrayList<>(o.subList(0, 1));
    }

    static List<Point> pairOf(Point p, List<Point> paired) {
        List<Point> o = new ArrayList<>(List.of(p));
        for (Point q : paired) if (q != p && q.y() == p.y() && Math.abs(q.x() - p.x()) + Math.abs(q.z() - p.z()) == 1) { o.add(q); break; }
        return o;
    }

    /** The highest double + single at the same height within 6 blocks (ramparts_0): [pair a, pair b, single], or empty. */
    static List<Point> topTriple(List<Point> chests) {
        List<Point> pairs = paired(chests, true), singles = paired(chests, false);
        List<Point> best = List.of();
        for (Point a : pairs) {
            List<Point> pr = pairOf(a, pairs);
            if (pr.size() < 2) continue;
            for (Point s : singles) {
                if (s.y() != a.y() || Math.abs(s.x() - a.x()) > 6 || Math.abs(s.z() - a.z()) > 6) continue;
                if (best.isEmpty() || a.y() > best.get(0).y()) best = List.of(pr.get(0), pr.get(1), s);
            }
        }
        return best;
    }

    /** 0 if the points spread more along x, 1 along z. */
    static int spreadAxis(List<Point> ps) {
        if (ps.isEmpty()) return 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Point p : ps) { minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x()); minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z()); }
        return maxX - minX >= maxZ - minZ ? 0 : 1;
    }

    static List<Point> largestCluster(List<Point> ps) {
        List<Point> best = new ArrayList<>();
        Set<Point> seen = new HashSet<>();
        for (Point s : ps) {
            if (seen.contains(s)) continue;
            List<Point> c = new ArrayList<>();
            Deque<Point> q = new ArrayDeque<>(List.of(s));
            seen.add(s);
            while (!q.isEmpty()) {
                Point p = q.poll(); c.add(p);
                for (Point o : ps) if (!seen.contains(o) && Math.abs(o.x() - p.x()) <= CLUSTER && Math.abs(o.y() - p.y()) <= CLUSTER && Math.abs(o.z() - p.z()) <= CLUSTER) { seen.add(o); q.add(o); }
            }
            if (c.size() > best.size()) best = c;
        }
        return best;
    }
}
