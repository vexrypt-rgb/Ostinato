/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.TerminalCostGoal;
import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * A goal region made of Cadenza candidates: every cell from which at least one of the targets can be acted on, each
 * with its terminal cost (the cheapest over the targets it reaches). Plain goals can ride along ({@code extras}, terminal
 * cost 0), e.g. dropped items or targets Cadenza found no cell for and the caller keeps on its old goal.
 * <p>
 * The heuristic is the stock {@link GoalBlock#calculate} to the nearest point of each target's candidate box, so it
 * never claims more than the stock heuristic to the nearest candidate would, and it costs O(targets) per node.
 */
public final class CadenzaGoal implements TerminalCostGoal {

    /** Cadenza v2: which order a batch of targets may be worked in from one cell. */
    public enum Ordering {
        /** The caller's order: a cell must reach the first target, and only the unbroken run from it counts as done there. */
        STRICT,
        /** Any order (the caller re-checks each target live: the builder's placeable set, the miner's known ores). */
        ANY
    }

    /**
     * Cadenza v2 options. {@link #OFF} is v1: terminal = the cheapest target's heading-blind cost.
     *
     * @param turn       charge the turn from the arrival heading to the aim instead of the heading-blind pitch term
     *                   (null: off)
     * @param batch      charge a cell for everything left to do: the targets it reaches (each one's cost, the turns
     *                   from one to the next), and for the ones it does not, a reposition each and the walk to the
     *                   nearest (needs {@code turn})
     * @param ordering   the batch order
     * @param reposition ticks one reposition costs on top of its walk (stop, re-plan, start)
     */
    public record Options(Cadenza.TurnModel turn, boolean batch, Ordering ordering, double reposition) {
        public static final Options OFF = new Options(null, false, Ordering.STRICT, 0);
        /** An estimate (live: a replan plus the stop and the start of a short walk); see the v2 handoff. */
        public static final double REPOSITION_TICKS = 8;

        public static Options turnAware(Cadenza.TurnModel turn) {
            return new Options(turn, false, Ordering.STRICT, 0);
        }

        public static Options batched(Cadenza.TurnModel turn, Ordering ordering) {
            return new Options(turn, true, ordering, REPOSITION_TICKS);
        }

        boolean v2() {
            return turn != null;
        }
    }

    private final Long2DoubleOpenHashMap terminal = new Long2DoubleOpenHashMap();
    /** How many targets each cell reaches (for the short-horizon ranking and the stats). */
    private final Long2IntOpenHashMap reaches = new Long2IntOpenHashMap();
    /** Per target with candidates: min x, y, z, max x, y, z of its cells. */
    private final int[][] boxes;
    private final Goal[] extras;
    /**
     * v2.1: the extras as a set, for equality. They are a union (in goal, heuristic and terminal cost alike do not
     * depend on their order), and callers list them by distance from the moving player, so the same extras can come in
     * another order on the next tick.
     */
    private final java.util.Set<Goal> extrasSet;
    /** Test harness only (v2.1 A/B): true restores v2's ordered comparison of the extras. Always false outside it. */
    static boolean orderedExtras = false;
    private final List<Cadenza.Result> results;
    private final Options options;
    /**
     * v2, per cell. Turn only: {base, yaw, pitch} per target the cell reaches. Batch: {fixed, yaw0, pitch0, yaw1,
     * pitch1}, the cost without the arrival turn and the two ends of the sweep (an arrival turns onto either end;
     * STRICT has one end, yaw1 is NaN).
     */
    private final Long2ObjectOpenHashMap<double[]> cells = new Long2ObjectOpenHashMap<>();

    public CadenzaGoal(List<Cadenza.Result> results, Goal... extras) {
        this(results, 0, extras);
    }

    public CadenzaGoal(List<Cadenza.Result> results, double shareBonus, Goal... extras) {
        this(results, shareBonus, Options.OFF, extras);
    }

    /**
     * @param shareBonus ticks taken off a cell's terminal cost per extra target it also reaches (short horizon; 0 = off).
     *                   The cost never goes below 0.
     */
    public CadenzaGoal(List<Cadenza.Result> results, double shareBonus, Options options, Goal... extras) {
        this.results = results;
        this.extras = extras;
        this.extrasSet = new java.util.HashSet<>(java.util.Arrays.asList(extras)); // (HashSet: tolerates a null extra, as the array did)
        this.options = options == null ? Options.OFF : options;
        terminal.defaultReturnValue(Double.NaN);
        List<int[]> b = new ArrayList<>();
        for (Cadenza.Result r : results) {
            if (!r.ok()) {
                continue;
            }
            int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (Cadenza.Candidate c : r.candidates) {
                long k = BetterBlockPos.longHash(c.x, c.y, c.z);
                double have = terminal.get(k);
                if (Double.isNaN(have) || c.terminal < have) {
                    terminal.put(k, c.terminal);
                }
                reaches.addTo(k, 1);
                box[0] = Math.min(box[0], c.x);
                box[1] = Math.min(box[1], c.y);
                box[2] = Math.min(box[2], c.z);
                box[3] = Math.max(box[3], c.x);
                box[4] = Math.max(box[4], c.y);
                box[5] = Math.max(box[5], c.z);
            }
            b.add(box);
        }
        boxes = b.toArray(new int[0][]);
        if (this.options.v2()) {
            buildCells(shareBonus);
            return;
        }
        if (shareBonus > 0) {
            for (var e : reaches.long2IntEntrySet()) {
                if (e.getIntValue() > 1) {
                    long k = e.getLongKey();
                    terminal.put(k, Math.max(0, terminal.get(k) - shareBonus * (e.getIntValue() - 1)));
                }
            }
        }
    }

    /** v2: the per-cell turn data, and {@link #terminal} as the cost with the heading unknown. */
    private void buildCells(double shareBonus) {
        Cadenza.TurnModel turn = options.turn;
        // ok targets in caller order, each with its cells
        List<Cadenza.Result> ok = new ArrayList<>();
        for (Cadenza.Result r : results) {
            if (r.ok()) {
                ok.add(r);
            }
        }
        int n = ok.size();
        Long2ObjectOpenHashMap<Cadenza.Candidate[]> per = new Long2ObjectOpenHashMap<>();
        for (int i = 0; i < n; i++) {
            for (Cadenza.Candidate c : ok.get(i).candidates) {
                Cadenza.Candidate[] a = per.computeIfAbsent(BetterBlockPos.longHash(c.x, c.y, c.z), k -> new Cadenza.Candidate[n]);
                a[i] = c;
            }
        }
        terminal.clear();
        for (var e : per.long2ObjectEntrySet()) {
            long k = e.getLongKey();
            Cadenza.Candidate[] a = e.getValue();
            double[] d;
            if (!options.batch) {
                int m = 0;
                for (Cadenza.Candidate c : a) {
                    if (c != null) m++;
                }
                double share = shareBonus * (m - 1);
                d = new double[m * 3];
                int j = 0;
                for (Cadenza.Candidate c : a) {
                    if (c != null) {
                        d[j++] = Math.max(0, c.base() - share);
                        d[j++] = c.yaw;
                        d[j++] = c.pitch;
                    }
                }
            } else {
                if (options.ordering == Ordering.STRICT && a[0] == null) {
                    continue; // must reach the first target
                }
                d = batchCell(a, turn);
            }
            cells.put(k, d);
            terminal.put(k, arrivalCost(d, Double.NaN));
        }
        // a target without candidates still has a box in the heuristic; keep boxes as built
    }

    /** {fixed, yaw0, pitch0, yaw1, pitch1} for a cell reaching the non-null targets of {@code a}. */
    private double[] batchCell(Cadenza.Candidate[] a, Cadenza.TurnModel turn) {
        int n = a.length;
        List<Cadenza.Candidate> seq = new ArrayList<>(n);
        boolean[] done = new boolean[n];
        if (options.ordering == Ordering.STRICT) {
            for (int i = 0; i < n && a[i] != null; i++) {
                seq.add(a[i]);
                done[i] = true;
            }
        } else {
            for (int i = 0; i < n; i++) {
                if (a[i] != null) {
                    seq.add(a[i]);
                    done[i] = true;
                }
            }
            // sweep the shortest arc: sort by yaw and start just after the widest gap
            seq.sort((p, q) -> Double.compare(norm(p.yaw), norm(q.yaw)));
            int m = seq.size(), start = 0;
            double widest = -1;
            for (int i = 0; i < m; i++) {
                double gap = i == 0 ? norm(seq.get(0).yaw) + 360 - norm(seq.get(m - 1).yaw) : norm(seq.get(i).yaw) - norm(seq.get(i - 1).yaw);
                if (gap > widest) {
                    widest = gap;
                    start = i;
                }
            }
            List<Cadenza.Candidate> rot = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                rot.add(seq.get((start + i) % m));
            }
            seq = rot;
        }
        double fixed = 0;
        for (int i = 0; i < seq.size(); i++) {
            Cadenza.Candidate c = seq.get(i);
            fixed += c.base();
            if (i > 0) {
                Cadenza.Candidate p = seq.get(i - 1);
                // at least a tick between two interactions: the next aim is set the tick after the last one ends
                fixed += Math.max(1, turn.between(p.yaw, p.pitch, c.yaw, c.pitch));
            }
        }
        // what this cell leaves: a reposition each, and the walk to the nearest
        Cadenza.Candidate first = seq.get(0), last = seq.get(seq.size() - 1);
        int left = 0;
        double walk = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (!done[i]) {
                left++;
                int[] bx = boxes[i];
                int dx = first.x < bx[0] ? first.x - bx[0] : first.x > bx[3] ? first.x - bx[3] : 0;
                int dy = first.y < bx[1] ? first.y - bx[1] : first.y > bx[4] ? first.y - bx[4] : 0;
                int dz = first.z < bx[2] ? first.z - bx[2] : first.z > bx[5] ? first.z - bx[5] : 0;
                walk = Math.min(walk, GoalBlock.calculate(dx, dy, dz));
            }
        }
        if (left > 0) {
            fixed += left * options.reposition + walk;
        }
        boolean oneEnd = options.ordering == Ordering.STRICT || seq.size() == 1;
        return new double[]{fixed, first.yaw, first.pitch, oneEnd ? Double.NaN : last.yaw, last.pitch};
    }

    private static double norm(double yaw) {
        double y = yaw % 360;
        return y < 0 ? y + 360 : y;
    }

    /** The cost of this cell's data for an arrival at this heading (NaN: unknown). */
    private double arrivalCost(double[] d, double heading) {
        Cadenza.TurnModel turn = options.turn;
        if (options.batch) {
            double t = turn.fromArrival(heading, d[1], d[2]);
            if (!Double.isNaN(d[3])) {
                t = Math.min(t, turn.fromArrival(heading, d[3], d[4]));
            }
            return d[0] + t;
        }
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < d.length; j += 3) {
            best = Math.min(best, d[j] + turn.fromArrival(heading, d[j + 1], d[j + 2]));
        }
        return best;
    }

    public Options options() {
        return options;
    }

    /** True when at least one target has a candidate (else this goal is just its extras). */
    public boolean hasCandidates() {
        return boxes.length > 0;
    }

    public int candidateCells() {
        return terminal.size();
    }

    public List<Cadenza.Result> results() {
        return results;
    }

    /** The plain goals riding along (a copy; diagnostics). */
    public Goal[] extras() {
        return extras.clone();
    }

    /** How many targets can be acted on from this cell (0 when it is not a candidate). */
    public int reachCount(int x, int y, int z) {
        return reaches.get(BetterBlockPos.longHash(x, y, z));
    }

    /** The number of candidate cells (diagnostics). */
    public int cellCount() {
        return terminal.size();
    }

    public boolean isCandidate(int x, int y, int z) {
        return terminal.containsKey(BetterBlockPos.longHash(x, y, z));
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (terminal.containsKey(BetterBlockPos.longHash(x, y, z))) {
            return true;
        }
        for (Goal g : extras) {
            if (g.isInGoal(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double terminalCost(int x, int y, int z) {
        double t = terminal.get(BetterBlockPos.longHash(x, y, z));
        if (Double.isNaN(t)) {
            return 0; // an extra goal cell
        }
        // a cell that is also an extra goal is worth at least as much as the extra
        for (Goal g : extras) {
            if (g.isInGoal(x, y, z)) {
                return 0;
            }
        }
        return t;
    }

    /** v2: the turn the arrival heading leaves (v1: the heading-blind cost). */
    @Override
    public double terminalCost(int x, int y, int z, int fromX, int fromY, int fromZ) {
        if (!options.v2()) {
            return terminalCost(x, y, z);
        }
        double[] d = cells.get(BetterBlockPos.longHash(x, y, z));
        if (d == null) {
            return 0; // an extra goal cell
        }
        for (Goal g : extras) {
            if (g.isInGoal(x, y, z)) {
                return 0;
            }
        }
        return arrivalCost(d, Cadenza.TurnModel.heading(fromX, fromZ, x, z));
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double h = Double.POSITIVE_INFINITY;
        for (int[] b : boxes) {
            int dx = x < b[0] ? x - b[0] : x > b[3] ? x - b[3] : 0;
            int dy = y < b[1] ? y - b[1] : y > b[4] ? y - b[4] : 0;
            int dz = z < b[2] ? z - b[2] : z > b[5] ? z - b[5] : 0;
            h = Math.min(h, GoalBlock.calculate(dx, dy, dz));
        }
        for (Goal g : extras) {
            h = Math.min(h, g.heuristic(x, y, z));
        }
        return h == Double.POSITIVE_INFINITY ? 0 : h;
    }

    /**
     * Same cells at the same terminal costs and equal extras (in any order). Processes rebuild their goal every tick, and
     * FORCE_REVALIDATE cancels a partial path whenever the new goal is not equal to the one it was planned for; the
     * planner's dead-node memory and timeout escalation also last only while the goal stays equal.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CadenzaGoal g)) {
            return false;
        }
        if (!options.equals(g.options) || !(orderedExtras ? java.util.Arrays.equals(extras, g.extras) : extrasSet.equals(g.extrasSet))) {
            return false;
        }
        // v2 cells carry the per-target aims, which the terminal map alone does not show
        if (!terminal.equals(g.terminal)) {
            return false;
        }
        if (!options.v2()) {
            return true;
        }
        // the caller may list the same targets in another order (sorted by distance from the moving player): only a
        // strict batch depends on that order
        return options.batch && options.ordering == Ordering.STRICT ? results.equals(g.results)
                : new java.util.HashSet<>(results).equals(new java.util.HashSet<>(g.results));
    }

    @Override
    public int hashCode() {
        return terminal.hashCode() * 31 + (orderedExtras ? java.util.Arrays.hashCode(extras) : extrasSet.hashCode());
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("CadenzaGoal{cells=").append(terminal.size()).append(" targets=");
        for (Cadenza.Result r : results) {
            s.append(r.target).append(':').append(r.status).append(' ');
        }
        return s.append("extras=").append(extras.length).append('}').toString();
    }
}
