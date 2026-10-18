/*
 * Ostinato: regression suite shared helpers. New file, replaces no Baritone code.
 */
package baritone.regress;

import baritone.Baritone;
import baritone.api.Settings;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Ostinato: configuration the Gradle {@code regress} tasks pass in as system properties, and helpers the stages share.
 * <ul>
 * <li>{@code ostinato.regress}: the stages only run when true (plain {@code ./gradlew test} skips them).</li>
 * <li>{@code ostinato.regress.out}: metrics directory (build/regress/metrics).</li>
 * <li>{@code ostinato.regress.stages}: comma list of stages to run (unit, pathbench, simdrill, combat); default all.</li>
 * <li>{@code ostinato.regress.worlds}: folder holding the map saves (pk_practice_parkour, ...); map courses are skipped without it.</li>
 * <li>{@code ostinato.regress.courses}: only these course names; {@code ostinato.regress.modes}: override every course's modes.</li>
 * <li>{@code ostinato.regress.commit}: commit id recorded in each metrics file.</li>
 * </ul>
 */
final class Regress {

    private Regress() {}

    static boolean on() {
        return Boolean.getBoolean("ostinato.regress");
    }

    static boolean stage(String name) {
        String s = System.getProperty("ostinato.regress.stages", "");
        return on() && (s.isBlank() || Arrays.asList(s.split(",")).contains(name));
    }

    static Path out() {
        return Path.of(System.getProperty("ostinato.regress.out", "build/regress/metrics"));
    }

    static String commit() {
        return System.getProperty("ostinato.regress.commit", "");
    }

    static Optional<Path> worlds() {
        String w = System.getProperty("ostinato.regress.worlds", "");
        if (w.isBlank()) w = Optional.ofNullable(System.getenv("OSTINATO_REGRESS_WORLDS")).orElse("");
        Path p = w.isBlank() ? null : Path.of(w);
        return p != null && Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
    }

    static List<String> list(String prop) {
        String s = System.getProperty(prop, "");
        if (s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String x : s.split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    /** Every setting's value, to put back after a stage changed them. */
    static Map<Settings.Setting<?>, Object> snapshot() {
        Map<Settings.Setting<?>, Object> m = new IdentityHashMap<>();
        for (Settings.Setting<?> s : Baritone.settings().allSettings) m.put(s, s.value);
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void restore(Map<Settings.Setting<?>, Object> snap) {
        for (Map.Entry<Settings.Setting<?>, Object> e : snap.entrySet()) ((Settings.Setting) e.getKey()).value = e.getValue();
    }

    /** Runs {@code body} with System.out muted (A* prints progress lines). */
    static <T> T quiet(Supplier<T> body) {
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream()));
        try {
            return body.get();
        } finally {
            System.setOut(out);
        }
    }

    static double median(List<Double> xs) {
        return pct(xs, 0.5);
    }

    static double pct(List<Double> xs, double p) {
        if (xs.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        int i = (int) Math.ceil(p * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, i)));
    }
}
