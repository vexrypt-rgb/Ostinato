/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.maze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

import static baritone.maze.ConnectedPlan.offset;

/**
 * Ostinato: run107. The fewest torches that keep a sealed complex free of hostile spawns. A hostile mob spawns only where
 * block light is 0 (overworld limit) and sky light is 0 underground, so every cell a mob could stand in must be within
 * {@link #REACH} steps of a torch through open air: a torch is light 14 and air takes 1 per step. Light is modelled only
 * through the complex's own air; containers count as walls, so the model never lights more than the world does.
 * <p>
 * Torches already standing stay; new ones are added for the cells they leave dark. For up to {@link #EXACT} new torches
 * the search is exhaustive, so the count is the minimum; above that it is greedy with a pass that drops any torch the
 * others make redundant. Ties go to the room centre and the tunnel centre line, then to the brightest darkest cell.
 */
public final class ConnectedLight {

    /** Torch light 14, minus 1 per air step; light 1 still stops a hostile spawn. */
    public static final int REACH = 13;
    /** New torches searched exhaustively. */
    static final int EXACT = 3;
    /** Candidate pool above which a 3-torch search is skipped for the greedy one. */
    static final int EXACT3_POOL = 220;
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private ConnectedLight() {}

    /** The planned torches and what the plan leaves dark (empty when the complex is safe). */
    public record Result(List<Long> torches, List<Long> dark, int targets, int pool, boolean exact) {}

    /**
     * Cells a hostile mob could stand in: open cells (air minus containers) with something that is not open under them.
     * Conservative: a cell over a barrel or a 1-high gap counts.
     */
    public static List<Long> spawnCells(Set<Long> open) {
        List<Long> out = new ArrayList<>();
        for (long k : open) if (!open.contains(offset(k, 0, -1, 0))) out.add(k);
        return out;
    }

    /** Air steps from the nearest source to every open cell within {@link #REACH}. */
    static Map<Long, Integer> reach(Set<Long> open, Collection<Long> sources) {
        Map<Long, Integer> dist = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long s : sources) {
            if (!open.contains(s) || dist.containsKey(s)) continue;
            dist.put(s, 0);
            queue.add(s);
        }
        while (!queue.isEmpty()) {
            long k = queue.poll();
            int d = dist.get(k);
            if (d >= REACH) continue;
            for (int[] f : FACES) {
                long n = offset(k, f[0], f[1], f[2]);
                if (open.contains(n) && !dist.containsKey(n)) {
                    dist.put(n, d + 1);
                    queue.add(n);
                }
            }
        }
        return dist;
    }

    /** Spawn cells no torch reaches. */
    public static List<Long> dark(Set<Long> open, Collection<Long> torches) {
        Map<Long, Integer> lit = reach(open, torches);
        List<Long> out = new ArrayList<>();
        for (long k : spawnCells(open)) if (!lit.containsKey(k)) out.add(k);
        return out;
    }

    /**
     * Plans the new torches.
     *
     * @param open      the complex's air minus the cells containers fill
     * @param existing  torches already standing in the complex
     * @param mount     cells a standing torch may go in (a solid, sturdy top under it)
     * @param preferred cells a torch looks best in
     */
    public static Result plan(Set<Long> open, Collection<Long> existing, LongPredicate mount, LongPredicate preferred) {
        List<Long> targets = dark(open, existing);
        if (targets.isEmpty()) return new Result(List.of(), List.of(), 0, 0, true);
        int t = targets.size();
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < t; i++) index.put(targets.get(i), i);
        int words = (t + 63) >>> 6;
        // Every spawn cell within reach of a dark one could light it. A torch is a spawn cell: it needs a floor.
        Set<Long> near = reach(open, targets).keySet();
        List<Long> pool = new ArrayList<>();
        List<long[]> cover = new ArrayList<>();
        List<int[]> steps = new ArrayList<>();
        for (long k : spawnCells(open)) {
            if (!near.contains(k) || !mount.test(k)) continue;
            long[] bits = new long[words];
            int[] dist = new int[t];
            java.util.Arrays.fill(dist, REACH + 1);
            boolean any = false;
            for (Map.Entry<Long, Integer> e : reach(open, List.of(k)).entrySet()) {
                Integer i = index.get(e.getKey());
                if (i == null) continue;
                bits[i >>> 6] |= 1L << (i & 63);
                dist[i] = e.getValue();
                any = true;
            }
            if (!any) continue;
            pool.add(k);
            cover.add(bits);
            steps.add(dist);
        }
        long[] all = new long[words];
        for (int i = 0; i < t; i++) all[i >>> 6] |= 1L << (i & 63);
        int[] best = null;
        int bestScore = -1;
        // 1, 2 and 3 torches, exhaustively; the first size with any full cover is the minimum.
        int n = pool.size();
        for (int size = 1; size <= EXACT && size <= n && best == null; size++) {
            if (size == 3 && n > EXACT3_POOL) break;
            int[] pick = new int[size];
            for (int i = 0; i < size; i++) pick[i] = i;
            while (true) {
                if (covers(cover, pick, all)) {
                    int score = score(pool, steps, pick, preferred);
                    if (score > bestScore) {
                        bestScore = score;
                        best = pick.clone();
                    }
                }
                int j = size - 1;
                while (j >= 0 && pick[j] == n - size + j) j--;
                if (j < 0) break;
                pick[j]++;
                for (int i = j + 1; i < size; i++) pick[i] = pick[i - 1] + 1;
            }
        }
        boolean exact = best != null;
        if (best == null) best = greedy(pool, cover, all, preferred);
        List<Long> torches = new ArrayList<>();
        for (int i : best) torches.add(pool.get(i));
        List<Long> after = new ArrayList<>(existing);
        after.addAll(torches);
        return new Result(List.copyOf(torches), dark(open, after), t, n, exact);
    }

    private static boolean covers(List<long[]> cover, int[] pick, long[] all) {
        for (int w = 0; w < all.length; w++) {
            long got = 0;
            for (int i : pick) got |= cover.get(i)[w];
            if (got != all[w]) return false;
        }
        return true;
    }

    /** Preferred spots first, then the light of the darkest newly lit cell (a torch set's distance is the min). */
    private static int score(List<Long> pool, List<int[]> steps, int[] pick, LongPredicate preferred) {
        int pref = 0;
        for (int i : pick) if (preferred.test(pool.get(i))) pref++;
        int far = 0;
        int t = steps.get(pick[0]).length;
        for (int c = 0; c < t; c++) {
            int d = REACH + 1;
            for (int i : pick) d = Math.min(d, steps.get(i)[c]);
            far = Math.max(far, d);
        }
        return pref * 100 + (14 - far);
    }

    private static int[] greedy(List<Long> pool, List<long[]> cover, long[] all, LongPredicate preferred) {
        long[] left = all.clone();
        List<Integer> pick = new ArrayList<>();
        while (nonZero(left)) {
            int best = -1, bestGain = 0;
            for (int i = 0; i < pool.size(); i++) {
                int gain = 0;
                long[] c = cover.get(i);
                for (int w = 0; w < left.length; w++) gain += Long.bitCount(c[w] & left[w]);
                boolean better = gain > bestGain
                        || gain == bestGain && gain > 0 && preferred.test(pool.get(i)) && !preferred.test(pool.get(best));
                if (better) {
                    best = i;
                    bestGain = gain;
                }
            }
            if (best < 0) break; // a dark cell no mount reaches: left for the dark list
            pick.add(best);
            long[] c = cover.get(best);
            for (int w = 0; w < left.length; w++) left[w] &= ~c[w];
        }
        // Drop any torch the others already cover, earliest picked first (the late ones fill the gaps).
        long[] reached = new long[all.length];
        for (int r : pick) for (int w = 0; w < reached.length; w++) reached[w] |= cover.get(r)[w];
        for (int i = 0; i < pick.size(); ) {
            long[] rest = new long[all.length];
            for (int r = 0; r < pick.size(); r++) {
                if (r == i) continue;
                for (int w = 0; w < rest.length; w++) rest[w] |= cover.get(pick.get(r))[w];
            }
            if (java.util.Arrays.equals(rest, reached)) pick.remove(i);
            else i++;
        }
        int[] out = new int[pick.size()];
        for (int i = 0; i < out.length; i++) out[i] = pick.get(i);
        return out;
    }

    private static boolean nonZero(long[] bits) {
        for (long b : bits) if (b != 0) return true;
        return false;
    }

    /** The open set of a complex: its air minus the cells containers fill. */
    public static Set<Long> open(Set<Long> air, Set<Long> containers) {
        Set<Long> open = new HashSet<>(air);
        open.removeAll(containers);
        return open;
    }
}
