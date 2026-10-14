/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calibration;

import baritone.Baritone;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.Moves;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Ostinato: measured movement costs.
 * <ul>
 * <li><b>Logging</b> (setting {@code logMovementTicks}): PathExecutor reports each finished or failed movement; one CSV
 * line per movement goes to {@code baritone/calibration/<mc version>/samples.csv}: move, class, planned ticks, actual
 * ticks, outcome, dy and whether the player was in water when it ended.</li>
 * <li><b>Tables</b>: {@link #aggregate} turns samples into {@code table.csv}: per {@link Moves} entry, the sample count, the
 * median actual/planned ratio and the failure rate. Tables are versioned by directory (one per MC version).</li>
 * <li><b>Use</b> (setting {@code useCalibratedCosts}): A* multiplies each move's planned cost by its median ratio; with
 * {@code riskAwareCosts} it also adds the expected failure cost p_fail * {@code riskRecoveryTicks}. Moves with fewer
 * than {@code calibrationMinSamples} samples keep factor 1 and no risk term. Both off = stock costs.</li>
 * </ul>
 * In the planner the arrays are read once per search ({@link #forSearch}), so a table reload never changes a running search.
 */
public final class CostCalibration {

    private CostCalibration() {}

    /** Per-search view: cost' = cost * mult[ordinal] + add[ordinal]. */
    public static final class Table {
        public final double[] mult;
        public final double[] add;

        Table(double[] mult, double[] add) {
            this.mult = mult;
            this.add = add;
        }
    }

    private static volatile Table loaded;
    private static volatile String loadedKey;

    /** The table A* should use for one search, or null for stock costs. */
    public static Table forSearch() {
        boolean calib = Baritone.settings().useCalibratedCosts.value;
        boolean risk = Baritone.settings().riskAwareCosts.value;
        if (!calib && !risk) return null;
        String key = calib + "|" + risk + "|" + Baritone.settings().riskRecoveryTicks.value + "|" + Baritone.settings().calibrationMinSamples.value;
        Table t = loaded;
        if (t == null || !key.equals(loadedKey)) {
            t = load(tableFile(), calib, risk);
            loaded = t;
            loadedKey = key;
        }
        return t;
    }

    /** Drop the cached table, e.g. after {@link #aggregate}. */
    public static void reload() {
        loaded = null;
    }

    public static String mcVersion() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion().name();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static Path dir() {
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve("calibration").resolve(mcVersion());
    }

    public static Path tableFile() {
        return dir().resolve("table.csv");
    }

    public static Path samplesFile() {
        return dir().resolve("samples.csv");
    }

    // ---- logging -----------------------------------------------------------------------------------------------

    private static BufferedWriter out;
    private static int unflushed;

    /** Called by PathExecutor when a movement ends. Cheap no-op unless logMovementTicks is on. */
    public static synchronized void record(Movement m, int ticks, boolean success, boolean startedWet) {
        if (!Baritone.settings().logMovementTicks.value || m.sourceMove == null) return;
        try {
            if (out == null) {
                Path f = samplesFile();
                Files.createDirectories(f.getParent());
                boolean fresh = !Files.exists(f);
                out = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (fresh) out.write("move,class,planned,actual,success,dy,wet\n");
            }
            out.write(m.sourceMove.name() + "," + m.getClass().getSimpleName() + "," + String.format(Locale.ROOT, "%.3f", m.getCost()) + ","
                    + ticks + "," + success + "," + (m.getDest().y - m.getSrc().y) + "," + startedWet + "\n");
            if (++unflushed >= 32) {
                out.flush();
                unflushed = 0;
            }
        } catch (IOException | RuntimeException e) {
            out = null;
        }
    }

    // ---- aggregation -------------------------------------------------------------------------------------------

    /** Sample rows -> table rows "move,n,ratio,pfail" (median actual/planned over successes, failures / all). */
    public static List<String> aggregate(List<String> samples) {
        Map<String, List<Double>> ratios = new TreeMap<>();
        Map<String, int[]> counts = new TreeMap<>();
        for (String line : samples) {
            String[] f = line.split(",");
            if (f.length < 5 || f[0].equals("move")) continue;
            double planned = Double.parseDouble(f[2]);
            int actual = Integer.parseInt(f[3]);
            boolean ok = Boolean.parseBoolean(f[4]);
            int[] c = counts.computeIfAbsent(f[0], k -> new int[2]);
            c[0]++;
            if (!ok) c[1]++;
            if (ok && planned > 0 && planned < 1e6) ratios.computeIfAbsent(f[0], k -> new ArrayList<>()).add(actual / planned);
        }
        List<String> rows = new ArrayList<>();
        rows.add("move,n,ratio,pfail");
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            List<Double> r = ratios.getOrDefault(e.getKey(), List.of());
            double med = 1;
            if (!r.isEmpty()) {
                List<Double> s = new ArrayList<>(r);
                Collections.sort(s);
                med = s.get(s.size() / 2);
            }
            rows.add(String.format(Locale.ROOT, "%s,%d,%.4f,%.4f", e.getKey(), e.getValue()[0], med, e.getValue()[1] / (double) e.getValue()[0]));
        }
        return rows;
    }

    /** Aggregate this version's samples.csv into table.csv. Returns the number of moves tabulated. */
    public static int aggregateFiles() throws IOException {
        List<String> rows = aggregate(Files.readAllLines(samplesFile()));
        Files.write(tableFile(), rows);
        reload();
        return rows.size() - 1;
    }

    static Table load(Path table, boolean calib, boolean risk) {
        List<String> rows;
        try {
            rows = Files.exists(table) ? Files.readAllLines(table) : List.of();
        } catch (IOException e) {
            rows = List.of();
        }
        return fromRows(rows, calib, risk, Baritone.settings().calibrationMinSamples.value, Baritone.settings().riskRecoveryTicks.value);
    }

    public static Table fromRows(List<String> rows, boolean calib, boolean risk, int minSamples, double recoveryTicks) {
        Moves[] all = Moves.values();
        double[] mult = new double[all.length];
        double[] add = new double[all.length];
        Arrays.fill(mult, 1);
        for (String line : rows) {
            String[] f = line.split(",");
            if (f.length < 4 || f[0].equals("move")) continue;
            Moves m;
            try {
                m = Moves.valueOf(f[0]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (Integer.parseInt(f[1]) < minSamples) continue;
            if (calib) mult[m.ordinal()] = Math.max(0.25, Math.min(4, Double.parseDouble(f[2])));
            if (risk) add[m.ordinal()] = Double.parseDouble(f[3]) * recoveryTicks;
        }
        return new Table(mult, add);
    }
}
