/*
 * Ostinato: regression suite report and baseline compare. New file, replaces no Baritone code.
 */
package baritone.regress;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Ostinato: turns the stage metrics into one pass/fail report against the stored baseline.
 * <pre>
 * RegressReport --metrics build/regress/metrics --baseline regress/baseline --junit build/test-results/test
 *               --out build/regress [--extra dir[:dir...]] [--write-baseline] [--no-fail] [--commit sha]
 * </pre>
 * The unit stage is read here from the JUnit XML of the normal test task. {@code --extra} folders are merged in, which
 * is how live-client stages run by outside scripts plug in. Exit status 1 when the verdict is FAIL (unless --no-fail).
 * Rules, per stage: an error is a failure; a metric past its tolerance in its bad direction fails or warns by its
 * severity; a case that was {@code ok} and is not now fails (a {@code timeout} only warns, it depends on machine speed);
 * a case now {@code fail}/{@code error} that was not already fails; a case that becomes {@code ok} is an improvement.
 */
public final class RegressReport {

    enum Level { FAIL, WARN, IMPROVED, INFO }

    record Finding(Level level, String stage, String text) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) a.put(args[i], args[++i]);
            else flags.add(args[i]);
        }
        Path metrics = Path.of(a.getOrDefault("--metrics", "build/regress/metrics"));
        Path baseline = Path.of(a.getOrDefault("--baseline", "regress/baseline"));
        Path out = Path.of(a.getOrDefault("--out", "build/regress"));
        String commit = a.getOrDefault("--commit", "");
        Files.createDirectories(metrics);
        if (a.containsKey("--junit")) {
            StageResult unit = junit(Path.of(a.get("--junit")));
            unit.commit = commit;
            unit.write(metrics);
        }
        Map<String, StageResult> cur = load(metrics);
        for (String extra : a.getOrDefault("--extra", "").split(java.io.File.pathSeparator)) {
            if (!extra.isBlank() && Files.isDirectory(Path.of(extra))) cur.putAll(load(Path.of(extra)));
        }
        if (flags.contains("--write-baseline")) {
            Files.createDirectories(baseline);
            int n = 0;
            for (StageResult r : cur.values()) {
                if (!r.status.equals("ok")) continue;
                r.write(baseline);
                n++;
            }
            System.out.println("[regress] baseline: wrote " + n + " stage files to " + baseline);
            return;
        }
        Map<String, StageResult> base = Files.isDirectory(baseline) ? load(baseline) : new TreeMap<>();
        List<Finding> fs = new ArrayList<>();
        Map<String, String> verdicts = new TreeMap<>();
        Set<String> stages = new TreeSet<>(cur.keySet());
        stages.addAll(base.keySet());
        for (String s : stages) verdicts.put(s, compare(s, cur.get(s), base.get(s), fs));
        boolean fail = fs.stream().anyMatch(f -> f.level == Level.FAIL);
        String md = markdown(commit, baseline, stages, cur, base, verdicts, fs, fail);
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.md"), md, StandardCharsets.UTF_8);
        Map<String, Object> js = new LinkedHashMap<>();
        js.put("verdict", fail ? "FAIL" : "PASS");
        js.put("commit", commit);
        js.put("stages", verdicts);
        List<Map<String, String>> fl = new ArrayList<>();
        for (Finding f : fs) fl.add(Map.of("level", f.level.name(), "stage", f.stage, "text", f.text));
        js.put("findings", fl);
        Files.writeString(out.resolve("report.json"), StageResult.GSON.toJson(js), StandardCharsets.UTF_8);
        System.out.println(md);
        System.out.println("[regress] verdict " + (fail ? "FAIL" : "PASS") + " -> " + out.resolve("report.md"));
        if (fail && !flags.contains("--no-fail")) System.exit(1);
    }

    static Map<String, StageResult> load(Path dir) throws IOException {
        Map<String, StageResult> m = new TreeMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                StageResult r = StageResult.read(p);
                if (r != null && r.stage != null) m.put(r.stage, r);
            }
        }
        return m;
    }

    /** The unit stage: every JUnit test of the normal test task (the regression stages themselves left out). */
    static StageResult junit(Path dir) throws Exception {
        StageResult r = new StageResult("unit:junit", "unit");
        if (!Files.isDirectory(dir)) {
            r.status = "error";
            r.note = "no JUnit results in " + dir + " (did the test task run?)";
            return r;
        }
        int tests = 0, failed = 0, skipped = 0;
        double secs = 0;
        var dbf = DocumentBuilderFactory.newInstance();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().startsWith("TEST-") && x.toString().endsWith(".xml")).sorted().toList()) {
                var doc = dbf.newDocumentBuilder().parse(p.toFile());
                NodeList tcs = doc.getElementsByTagName("testcase");
                for (int i = 0; i < tcs.getLength(); i++) {
                    Element tc = (Element) tcs.item(i);
                    String cls = tc.getAttribute("classname");
                    if (cls.startsWith("baritone.regress.")) continue;
                    String outcome = tc.getElementsByTagName("failure").getLength() > 0 || tc.getElementsByTagName("error").getLength() > 0 ? "fail"
                            : tc.getElementsByTagName("skipped").getLength() > 0 ? "skip" : "ok";
                    tests++;
                    if (outcome.equals("fail")) failed++;
                    if (outcome.equals("skip")) skipped++;
                    try {
                        secs += Double.parseDouble(tc.getAttribute("time"));
                    } catch (NumberFormatException ignored) {
                    }
                    StageResult.Case c = r.add(cls.substring(cls.lastIndexOf('.') + 1) + "." + tc.getAttribute("name"), outcome);
                    if (outcome.equals("fail")) {
                        Element f = (Element) (tc.getElementsByTagName("failure").getLength() > 0 ? tc.getElementsByTagName("failure") : tc.getElementsByTagName("error")).item(0);
                        String msg = f.getAttribute("message");
                        c.detail = msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
                    }
                }
            }
        }
        r.metric("tests", tests, "info", "warn", 0, 0, "");
        r.metric("failed", failed, "lower", "fail", 0, 0, "");
        r.metric("skipped", skipped, "lower", "warn", 0, 0, "");
        r.metric("seconds", Math.round(secs), "lower", "warn", 30, 0.5, "s");
        return r;
    }

    static String compare(String s, StageResult c, StageResult b, List<Finding> fs) {
        if (c == null) {
            fs.add(new Finding(Level.WARN, s, "in the baseline but not run"));
            return "NOT RUN";
        }
        if (c.status.equals("error")) {
            fs.add(new Finding(Level.FAIL, s, "stage error: " + c.note));
            return "FAIL";
        }
        if (c.status.equals("skipped")) {
            if (b != null) fs.add(new Finding(Level.WARN, s, "skipped now: " + c.note));
            return "SKIPPED";
        }
        int before = fs.size();
        if (b == null) {
            fs.add(new Finding(Level.INFO, s, "new stage, no baseline yet"));
            for (Map.Entry<String, StageResult.Case> e : c.cases.entrySet())
                if (e.getValue().outcome.equals("fail") || e.getValue().outcome.equals("error"))
                    fs.add(new Finding(Level.FAIL, s, e.getKey() + ": " + e.getValue().outcome + detail(e.getValue())));
            return verdict(fs, before, "NEW");
        }
        for (StageResult.Metric m : c.metrics) {
            StageResult.Metric bm = b.metric(m.name);
            if (bm == null || m.better.equals("info")) continue;
            double tol = m.abs + m.rel * Math.abs(bm.value);
            double worse = m.better.equals("higher") ? bm.value - m.value : m.value - bm.value;
            Level bad = m.severity.equals("fail") ? Level.FAIL : Level.WARN;
            if (worse > tol + 1e-9) fs.add(new Finding(bad, s, String.format(Locale.ROOT, "%s %s -> %s%s (tolerance %s)", m.name, num(bm.value), num(m.value), m.unit.isEmpty() ? "" : " " + m.unit, num(tol))));
            else if (-worse > tol + 1e-9 && m.severity.equals("fail")) fs.add(new Finding(Level.IMPROVED, s, String.format(Locale.ROOT, "%s %s -> %s", m.name, num(bm.value), num(m.value))));
        }
        int changed = 0;
        for (Map.Entry<String, StageResult.Case> e : c.cases.entrySet()) {
            StageResult.Case cc = e.getValue(), bc = b.cases.get(e.getKey());
            boolean badNow = cc.outcome.equals("fail") || cc.outcome.equals("error");
            if (bc == null) {
                if (badNow) fs.add(new Finding(Level.FAIL, s, e.getKey() + ": new case, " + cc.outcome + detail(cc)));
                continue;
            }
            if (bc.outcome.equals(cc.outcome)) {
                boolean costMoved = cc.cost != null && bc.cost != null && Math.abs(cc.cost - bc.cost) > Math.max(1e-3, 0.005 * Math.abs(bc.cost));
                boolean nodesMoved = cc.nodes != null && bc.nodes != null && !cc.nodes.equals(bc.nodes);
                if (cc.outcome.equals("ok") && (costMoved || nodesMoved)) {
                    if (changed++ < 25) fs.add(new Finding(Level.INFO, s, String.format(Locale.ROOT, "%s: cost %s -> %s, nodes %s -> %s", e.getKey(),
                            num(bc.cost == null ? Double.NaN : bc.cost), num(cc.cost == null ? Double.NaN : cc.cost), bc.nodes, cc.nodes)));
                }
                continue;
            }
            if (bc.outcome.equals("ok")) {
                fs.add(new Finding(cc.outcome.equals("timeout") ? Level.WARN : Level.FAIL, s, e.getKey() + ": ok -> " + cc.outcome + detail(cc)));
            } else if (cc.outcome.equals("ok")) {
                fs.add(new Finding(Level.IMPROVED, s, e.getKey() + ": " + bc.outcome + " -> ok" + detail(cc)));
            } else if (badNow) {
                fs.add(new Finding(Level.FAIL, s, e.getKey() + ": " + bc.outcome + " -> " + cc.outcome + detail(cc)));
            } else {
                fs.add(new Finding(Level.INFO, s, e.getKey() + ": " + bc.outcome + " -> " + cc.outcome));
            }
        }
        if (changed > 25) fs.add(new Finding(Level.INFO, s, (changed - 25) + " more cost/node changes"));
        for (String k : b.cases.keySet()) if (!c.cases.containsKey(k)) fs.add(new Finding(Level.INFO, s, k + ": gone (was " + b.cases.get(k).outcome + ")"));
        return verdict(fs, before, "PASS");
    }

    private static String verdict(List<Finding> fs, int from, String pass) {
        List<Finding> mine = fs.subList(from, fs.size());
        if (mine.stream().anyMatch(f -> f.level == Level.FAIL)) return "FAIL";
        if (mine.stream().anyMatch(f -> f.level == Level.WARN)) return pass + " (warn)";
        return pass;
    }

    private static String detail(StageResult.Case c) {
        return c.detail == null || c.detail.isEmpty() ? "" : " (" + c.detail + ")";
    }

    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String markdown(String commit, Path baseline, Set<String> stages, Map<String, StageResult> cur, Map<String, StageResult> base,
                                   Map<String, String> verdicts, List<Finding> fs, boolean fail) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Ostinato regression report: ").append(fail ? "FAIL" : "PASS").append("\n\n");
        sb.append("- Commit: `").append(commit.isEmpty() ? "?" : commit).append("`\n");
        String bc = base.values().stream().map(r -> r.commit).filter(x -> x != null && !x.isEmpty()).findFirst().orElse("?");
        sb.append("- Baseline: `").append(baseline).append("` (commit `").append(bc).append("`)\n");
        sb.append("- Written: ").append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))).append("\n\n");
        sb.append("| stage | verdict | key metrics (now, baseline) | note |\n|---|---|---|---|\n");
        for (String s : stages) {
            StageResult c = cur.get(s), b = base.get(s);
            StringBuilder km = new StringBuilder();
            if (c != null) {
                for (StageResult.Metric m : c.metrics) {
                    if (km.length() > 0) km.append(", ");
                    StageResult.Metric bm = b == null ? null : b.metric(m.name);
                    km.append(m.name).append(' ').append(num(m.value));
                    if (bm != null && bm.value != m.value) km.append(" (").append(num(bm.value)).append(')');
                }
            }
            String note = c == null ? "" : c.note.replace("|", "/");
            sb.append("| ").append(s).append(" | ").append(verdicts.get(s)).append(" | ").append(km).append(" | ").append(note).append(" |\n");
        }
        for (Level l : Level.values()) {
            List<Finding> xs = fs.stream().filter(f -> f.level == l).toList();
            if (xs.isEmpty()) continue;
            sb.append("\n## ").append(l).append(" (").append(xs.size()).append(")\n\n");
            for (Finding f : xs) sb.append("- `").append(f.stage).append("` ").append(f.text).append('\n');
        }
        return sb.toString();
    }
}
