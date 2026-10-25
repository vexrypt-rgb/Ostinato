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

package baritone.pathing.decide;

import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ostinato: tiny trainable decision matrix for pathfinding choices. Rows are categories (terrain bucket x distance
 * bucket, classified from the world around the segment); columns are {@link Action}s; each cell is the mean measured
 * score (travel ticks + plan ticks, failures penalised) and its sample count. Trained by pathbench
 * (PathBenchTest#trainDecisionMatrix), exported/loaded as a plain TSV, so it is replaceable by hand or by another trainer.
 * Off by default (pathDecisionMatrix); with too few samples in a row it says nothing and the normal settings decide.
 * Categories are kept coarse on purpose so autotune can later key per biome / jump type / terrain transition.
 */
public final class PathDecisionMatrix {

    /** Pathfinder backends the matrix may pick. Add new columns at the end; unknown names in a file are skipped. */
    public enum Action {
        ASTAR(1, false), WA15(1.5, false), WA2(2, false), WA3(3, false), HPA(1, true), HPA_WA2(2, true);

        public final double weight;
        public final boolean hierarchical;

        Action(double weight, boolean hierarchical) {
            this.weight = weight;
            this.hierarchical = hierarchical;
        }
    }

    public enum Terrain { FLAT, HILLS, CAVES, WATER }

    public enum Dist {
        NEAR(0), MID(64), FAR(256);
        final int from;

        Dist(int from) {
            this.from = from;
        }

        public static Dist of(double blocks) {
            return blocks >= FAR.from ? FAR : blocks >= MID.from ? MID : NEAR;
        }
    }

    public record Category(Terrain terrain, Dist dist) {
        public String key() {
            return terrain + "/" + dist;
        }
    }

    /** Score of one failed plan, in ticks: worse than any real plan (bench paths are ~1200 ticks, plans <= 200). */
    public static final double FAIL_SCORE = 10_000;

    private final Map<String, double[]> sum = new TreeMap<>();
    private final Map<String, int[]> n = new TreeMap<>();
    public int minSamples = 3;

    public void record(Category c, Action a, double score) {
        sum.computeIfAbsent(c.key(), k -> new double[Action.values().length])[a.ordinal()] += score;
        n.computeIfAbsent(c.key(), k -> new int[Action.values().length])[a.ordinal()]++;
    }

    public static double score(boolean ok, double pathCostTicks, double planMs) {
        return ok ? pathCostTicks + planMs / 50.0 : FAIL_SCORE;
    }

    /** Lowest mean score among actions with enough samples, or null when the row is unknown / too thin. */
    public Action choose(Category c) {
        double[] s = sum.get(c.key());
        int[] k = n.get(c.key());
        if (s == null) return null;
        Action best = null;
        double bestMean = Double.MAX_VALUE;
        for (Action a : Action.values()) {
            int i = a.ordinal();
            if (k[i] < minSamples) return null; // every column measured, or no opinion
            double m = s[i] / k[i];
            if (m < bestMean) {
                bestMean = m;
                best = a;
            }
        }
        return best;
    }

    public double mean(Category c, Action a) {
        double[] s = sum.get(c.key());
        int[] k = n.get(c.key());
        return s == null || k[a.ordinal()] == 0 ? Double.NaN : s[a.ordinal()] / k[a.ordinal()];
    }

    // ---------------------------------------------------------------- export / load

    public String export() {
        StringBuilder b = new StringBuilder("# Ostinato path decision matrix v1: category\taction\tmeanScore\tsamples\n");
        for (String key : sum.keySet()) {
            for (Action a : Action.values()) {
                int k = n.get(key)[a.ordinal()];
                if (k > 0) b.append(key).append('\t').append(a).append('\t')
                        .append(String.format(Locale.ROOT, "%.3f", sum.get(key)[a.ordinal()] / k)).append('\t').append(k).append('\n');
            }
        }
        return b.toString();
    }

    public static PathDecisionMatrix parse(String text) {
        PathDecisionMatrix m = new PathDecisionMatrix();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            if (p.length < 4) continue;
            Action a;
            try {
                a = Action.valueOf(p[1]);
            } catch (IllegalArgumentException e) {
                continue; // a column this build doesn't have
            }
            int k = Integer.parseInt(p[3]);
            double mean = Double.parseDouble(p[2]);
            m.sum.computeIfAbsent(p[0], x -> new double[Action.values().length])[a.ordinal()] += mean * k;
            m.n.computeIfAbsent(p[0], x -> new int[Action.values().length])[a.ordinal()] += k;
        }
        return m;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, export());
    }

    public static PathDecisionMatrix load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    // ---------------------------------------------------------------- features

    /** Classifies a segment from the blocks around its straight line. Cheap: ~17 column scans of at most 48 blocks. */
    public static Category categorize(CalculationContext ctx, int sx, int sy, int sz, int gx, int gz, double blocks) {
        Dist dist = Dist.of(blocks);
        int roof = 0;
        for (int dy = 2; dy <= 24; dy++) {
            if (!ctx.get(sx, sy + dy, sz).isAir()) {
                roof = dy;
                break;
            }
        }
        List<Integer> heights = new ArrayList<>();
        int water = 0, samples = 16;
        for (int i = 0; i <= samples; i++) {
            int x = sx + (gx - sx) * i / samples, z = sz + (gz - sz) * i / samples;
            if (!ctx.isLoaded(x, z)) continue;
            for (int y = sy + 24; y >= sy - 24; y--) {
                BlockState st = ctx.get(x, y, z);
                if (st.isAir()) continue;
                if (MovementHelper.isWater(st)) water++;
                heights.add(y);
                break;
            }
        }
        Terrain t;
        if (roof > 0 && roof < 24 && !heights.isEmpty() && heights.get(0) > sy + 1) t = Terrain.CAVES;
        else if (!heights.isEmpty() && water * 3 >= heights.size()) t = Terrain.WATER;
        else if (stdev(heights) > 3) t = Terrain.HILLS;
        else t = Terrain.FLAT;
        return new Category(t, dist);
    }

    private static double stdev(List<Integer> xs) {
        if (xs.size() < 2) return 0;
        double m = 0;
        for (int x : xs) m += x;
        m /= xs.size();
        double v = 0;
        for (int x : xs) v += (x - m) * (x - m);
        return Math.sqrt(v / xs.size());
    }

    // ---------------------------------------------------------------- runtime instance

    private static volatile PathDecisionMatrix active;
    private static volatile String activeFile;

    /** The loaded matrix for {@code file}, reloading when the setting changes; null when missing or unreadable. */
    public static PathDecisionMatrix active(Path file) {
        String f = file.toString();
        if (!f.equals(activeFile)) {
            activeFile = f;
            try {
                active = Files.exists(file) ? load(file) : null;
            } catch (IOException | RuntimeException e) {
                active = null;
            }
        }
        return active;
    }
}
