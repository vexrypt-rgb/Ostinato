package baritone.utils.fault;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Structured fault record. No Minecraft imports, so the same package is shared with Ostinato.
 *
 * Every fault becomes one JSON line in {@code faults.jsonl}: t, sev, code, evidence, hint,
 * plus the context supplier's fields (phase, pos, dim, child, inv). A fault opens an
 * episode; the next real progress signal closes it with the seconds it cost.
 * {@link #writeSummary} writes per-code counts and seconds lost to {@code run-summary.json},
 * sorted by cost, so a run can be judged without reading the whole log.
 */
public final class FaultBook {

    public enum Severity { INFO, WARN, RECOVERY, ERROR }

    public static final String EVENTS = "faults.jsonl";
    public static final String SUMMARY = "run-summary.json";

    private static Path dir = Path.of("baritone");
    private static Supplier<Map<String, String>> context = Map::of;
    private static Function<String, String> hints = c -> "";
    private static long runStart = System.currentTimeMillis();

    private static final Map<String, Long> openEpisodes = new LinkedHashMap<>();
    private static final Map<String, int[]> counts = new TreeMap<>(); // code -> [seen, recovered]
    private static final Map<String, Long> lostMs = new TreeMap<>();
    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private static final LoopDetector loops = new LoopDetector(4, 60_000);

    private FaultBook() {}

    public static synchronized void configure(Path d, Supplier<Map<String, String>> ctx, Function<String, String> hint) {
        dir = d;
        context = ctx;
        hints = hint;
    }

    /** New run: clears episodes and counters and truncates the event file. */
    public static synchronized void reset(long now) {
        runStart = now;
        openEpisodes.clear();
        counts.clear();
        lostMs.clear();
        recent.clear();
        loops.clear();
        write(EVENTS, "", false);
    }

    public static Severity severityOf(String code) {
        if (code == null || code.isEmpty()) return Severity.WARN;
        return switch (code.charAt(0)) {
            case 'E' -> Severity.ERROR;
            case 'S' -> Severity.RECOVERY;
            case 'I' -> Severity.INFO;
            default -> Severity.WARN;
        };
    }

    public static synchronized void record(String code, String evidence, long now) {
        counts.computeIfAbsent(code, k -> new int[2])[0]++;
        openEpisodes.putIfAbsent(code, now);
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("type", "fault");
        ev.put("t", secs(now - runStart));
        ev.put("sev", severityOf(code).name());
        ev.put("code", code);
        ev.put("evidence", evidence);
        ev.put("hint", safeHint(code));
        try {
            ev.putAll(context.get());
        } catch (Throwable ignored) {}
        write(EVENTS, Json.obj(ev) + "\n", true);
        recent.addLast(ev.get("t") + "s " + code + " " + evidence);
        while (recent.size() > 30) recent.removeFirst();
    }

    /** Real progress (moved or inventory changed): closes every open episode as recovered. */
    public static synchronized void progress(long now) {
        if (openEpisodes.isEmpty()) return;
        for (Map.Entry<String, Long> e : openEpisodes.entrySet()) {
            long lost = Math.max(0, now - e.getValue());
            lostMs.merge(e.getKey(), lost, Long::sum);
            counts.computeIfAbsent(e.getKey(), k -> new int[2])[1]++;
            Map<String, String> ev = new LinkedHashMap<>();
            ev.put("type", "episode");
            ev.put("t", secs(now - runStart));
            ev.put("code", e.getKey());
            ev.put("outcome", "recovered");
            ev.put("lost_s", secs(lost));
            write(EVENTS, Json.obj(ev) + "\n", true);
        }
        openEpisodes.clear();
    }

    /** Feed the active child name each tick; returns "A<->B x4" when a flip loop completes, else null. */
    public static synchronized String child(String name, long now) {
        return loops.feed(name, now);
    }

    public static synchronized String recentText() {
        return recent.isEmpty() ? "(no faults this run)" : String.join("\n", recent);
    }

    public static synchronized long lostMs(long now) {
        long s = 0;
        for (long v : lostMs.values()) s += v;
        for (long v : openEpisodes.values()) s += Math.max(0, now - v);
        return s;
    }

    public static synchronized String summaryJson(long now, String outcome) {
        StringBuilder codes = new StringBuilder("[");
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(cost(b.getKey(), now), cost(a.getKey(), now)))
                .forEach(e -> {
                    if (codes.length() > 1) codes.append(',');
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("code", e.getKey());
                    m.put("sev", severityOf(e.getKey()).name());
                    m.put("count", String.valueOf(e.getValue()[0]));
                    m.put("recovered", String.valueOf(e.getValue()[1]));
                    m.put("lost_s", secs(cost(e.getKey(), now)));
                    m.put("open", String.valueOf(openEpisodes.containsKey(e.getKey())));
                    m.put("hint", safeHint(e.getKey()));
                    codes.append(Json.obj(m));
                });
        codes.append(']');
        return "{\"outcome\":" + Json.str(outcome)
                + ",\"run_s\":" + Json.str(secs(now - runStart))
                + ",\"lost_s\":" + Json.str(secs(lostMs(now)))
                + ",\"codes\":" + codes + "}";
    }

    public static void writeSummary(long now, String outcome) {
        write(SUMMARY, summaryJson(now, outcome) + "\n", false);
    }

    private static long cost(String code, long now) {
        long c = lostMs.getOrDefault(code, 0L);
        Long open = openEpisodes.get(code);
        return open == null ? c : c + Math.max(0, now - open);
    }

    private static String safeHint(String code) {
        try {
            String h = hints.apply(code);
            return h == null ? "" : h;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }

    private static void write(String name, String body, boolean append) {
        try {
            Files.createDirectories(dir);
            if (append) {
                Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {}
    }
}
