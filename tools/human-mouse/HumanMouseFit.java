// Ostinato: fits a humanized-mouse profile from mouse-capture recordings (tools/human-mouse, see README.md).
// Dependency-free single-file program: java HumanMouseFit.java [-o profile.json] session1.json [session2.json ...]
//                                      java HumanMouseFit.java --synthetic -o example-profile.json
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class HumanMouseFit {
    static final int N = 40;          // template resolution: N+1 points over normalized movement time
    static final double GRID = 5.0;   // ms resampling grid
    static final int MAX_TEMPLATES = 500;

    public static void main(String[] args) throws IOException {
        String out = "human-mouse-profile.json";
        boolean synthetic = false, includeDesktop = false;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-o": out = args[++i]; break;
                case "--synthetic": synthetic = true; break;
                case "--include-desktop": includeDesktop = true; break;
                default: inputs.add(args[i]);
            }
        }
        Map<String, Object> profile;
        if (synthetic) profile = synthetic(new Random(26_03));
        else {
            List<Path> files = new ArrayList<>();
            for (String s : inputs) {
                Path p = Paths.get(s);
                if (Files.isDirectory(p)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.json")) { for (Path f : ds) files.add(f); }
                else files.add(p);
            }
            if (files.isEmpty()) { System.err.println("usage: java HumanMouseFit.java [-o profile.json] <session.json|dir> ...  |  --synthetic -o out.json"); System.exit(2); }
            profile = fit(files, includeDesktop);
        }
        Files.write(Paths.get(out), Json.write(profile).getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out);
    }

    // ------------------------------------------------------------------ segmentation
    static final class Seg {
        String kind; double d, w, u, onsetMs, dur, angle; double[] along, perp; double[] speed; int subs; boolean overshoot; double peakAt, maxPerp;
        double[] residual; // high-passed position noise along the path (counts)
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fit(List<Path> files, boolean includeDesktop) throws IOException {
        List<Seg> segs = new ArrayList<>();
        List<Double> noise = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        int rounds = 0;
        for (Path f : files) {
            Map<String, Object> s = (Map<String, Object>) Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            if (!(s.get("rounds") instanceof List)) { System.out.println("skipping " + f.getFileName() + " (not a mouse-capture session)"); continue; }
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("file", f.getFileName().toString()); src.put("sessionId", s.get("sessionId")); src.put("settings", s.get("settings"));
            sources.add(src);
            for (Object ro : (List<Object>) s.get("rounds")) {
                Map<String, Object> r = (Map<String, Object>) ro;
                String mode = (String) r.get("mode");
                if (!"lock".equals(mode) && !includeDesktop) continue;
                rounds++;
                Track tr = Track.of(r);
                Map<String, Object> st = (Map<String, Object>) r.get("settings");
                double gain = num(st.get("pageGainPxPerCount"), 1), dpc = num(st.get("mcDegreesPerCount"), 0.15);
                if ("abs".equals(mode)) gain = 1; // absolute px
                for (Object to : (List<Object>) r.get("trials")) {
                    Map<String, Object> t = (Map<String, Object>) to;
                    String kind = (String) t.get("kind");
                    if (!Boolean.TRUE.equals(t.get("hit")) || t.get("tEnd") == null) continue;
                    double tOn = num(t.get("tOn"), 0), tEnd = num(t.get("tEnd"), 0);
                    double tx, ty, w, u = num(t.get("urgency"), 0.5);
                    if ("mcturn".equals(kind) || "reaction".equals(kind)) {
                        Map<String, Object> tg = (Map<String, Object>) t.get("target");
                        tx = wrap(num(tg.get("yaw"), 0) - num(t.get("fromYaw"), 0)) / dpc;
                        ty = (num(tg.get("pitch"), 0) - num(t.get("fromPitch"), 0)) / dpc;
                        w = num(tg.get("angSizeDeg"), 2) / dpc;
                    } else {
                        Map<String, Object> tg = (Map<String, Object>) t.get("target"), fr = (Map<String, Object>) t.get("from");
                        if (tg == null || fr == null) continue;
                        tx = (num(tg.get("x"), 0) - num(fr.get("x"), 0)) / gain;
                        ty = (num(tg.get("y"), 0) - num(fr.get("y"), 0)) / gain;
                        w = 2 * num(tg.get("r"), 10) / gain;
                    }
                    Seg sg = segment(tr, tOn, tEnd, tx, ty, w, u);
                    if (sg != null) { sg.kind = kind; segs.add(sg); for (double v : sg.residual) noise.add(v); }
                }
            }
        }
        if (segs.size() < 10) throw new IllegalStateException("only " + segs.size() + " usable movements found; record a few more rounds");
        System.out.println("rounds " + rounds + ", movements " + segs.size());
        return build(segs, noise, sources, false);
    }

    /** Cumulative position track of a round, in counts (lock) or px (abs). */
    static final class Track {
        double[] t, x, y;
        @SuppressWarnings("unchecked")
        static Track of(Map<String, Object> r) {
            Map<String, Object> s = (Map<String, Object>) r.get("samples");
            List<Object> T = (List<Object>) s.get("t"), X = (List<Object>) s.get("x"), Y = (List<Object>) s.get("y");
            Track k = new Track(); int n = T.size(); k.t = new double[n]; k.x = new double[n]; k.y = new double[n];
            boolean abs = "abs".equals(r.get("mode")); double cx = 0, cy = 0;
            for (int i = 0; i < n; i++) {
                k.t[i] = num(T.get(i), 0);
                if (abs) { cx = num(X.get(i), 0); cy = num(Y.get(i), 0); } else { cx += num(X.get(i), 0); cy += num(Y.get(i), 0); }
                k.x[i] = cx; k.y[i] = cy;
            }
            return k;
        }
        /** position at time q: the last sample at or before q (a mouse report is a step) */
        double[] at(double q) {
            int lo = 0, hi = t.length - 1;
            if (hi < 0 || q < t[0]) return new double[]{hi < 0 ? 0 : x[0] - 0, hi < 0 ? 0 : y[0]};
            while (lo < hi) { int m = (lo + hi + 1) >>> 1; if (t[m] <= q) lo = m; else hi = m - 1; }
            return new double[]{x[lo], y[lo]};
        }
    }

    static Seg segment(Track tr, double tOn, double tEnd, double tx, double ty, double w, double u) {
        double D = Math.hypot(tx, ty);
        if (D < 3 || tEnd - tOn < 60 || tEnd - tOn > 6000) return null;
        int n = (int) ((tEnd - tOn) / GRID) + 1;
        double[] px = new double[n], py = new double[n];
        double[] p0 = tr.at(tOn);
        for (int i = 0; i < n; i++) { double[] p = tr.at(tOn + i * GRID); px[i] = p[0] - p0[0]; py[i] = p[1] - p0[1]; }
        double[] sx = smooth(px, 5), sy = smooth(py, 5);   // 25 ms box filter kills report quantization
        double[] v = new double[n];
        for (int i = 1; i < n; i++) v[i] = Math.hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1]) / GRID;
        double peak = 0; int pk = 0; for (int i = 0; i < n; i++) if (v[i] > peak) { peak = v[i]; pk = i; }
        if (peak <= 0) return null;
        int on = 0; while (on < pk && v[on] < 0.06 * peak) on++;
        int off = n - 1; while (off > pk && v[off] < 0.04 * peak) off--;
        if (off - on < 6) return null;
        Seg s = new Seg();
        s.d = D; s.w = Math.max(1, w); s.u = u; s.angle = Math.atan2(ty, tx);
        s.onsetMs = on * GRID; s.dur = (off - on) * GRID;
        double ux = tx / D, uy = ty / D, ox = px[on], oy = py[on];
        s.along = new double[N + 1]; s.perp = new double[N + 1];
        for (int k = 0; k <= N; k++) {
            double f = on + (off - on) * (double) k / N; int i = (int) f; double a = f - i; int j = Math.min(i + 1, n - 1);
            double qx = px[i] + (px[j] - px[i]) * a - ox, qy = py[i] + (py[j] - py[i]) * a - oy;
            s.along[k] = (qx * ux + qy * uy) / D; s.perp[k] = (-qx * uy + qy * ux) / D;
        }
        // submovements: speed peaks above 10% of the main one with a dip below 70% of the lower peak between them
        // (on a 45 ms smoothed speed, so report quantization doesn't count as a submovement)
        double[] vs = smooth(v, 4);
        int subs = 0; double lastPk = 0, minSince = Double.MAX_VALUE;
        for (int i = on + 1; i < off; i++) {
            if (vs[i] < minSince) minSince = vs[i];
            if (vs[i] >= vs[i - 1] && vs[i] >= vs[i + 1] && vs[i] > 0.15 * peak) {
                if (subs == 0 || minSince < 0.7 * Math.min(vs[i], lastPk)) { subs++; lastPk = vs[i]; minSince = vs[i]; }
                else lastPk = Math.max(lastPk, vs[i]);
            }
        }
        s.subs = Math.max(1, subs);
        double mx = 0, mp = 0; for (int k = 0; k <= N; k++) { mx = Math.max(mx, s.along[k]); mp = Math.max(mp, Math.abs(s.perp[k])); }
        s.overshoot = mx > 1 + 0.5 * s.w / D; s.maxPerp = mp; s.peakAt = (pk - on) / (double) (off - on);
        // tremor: raw position minus a 60 ms smoothed path, from the movement and its dwell
        double[] lx = smooth(px, 12), ly = smooth(py, 12);
        s.residual = new double[n];
        for (int i = 0; i < n; i++) s.residual[i] = (px[i] - lx[i]) * ux + (py[i] - ly[i]) * uy;
        return s;
    }

    static double[] smooth(double[] a, int half) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) { double s = 0; int c = 0; for (int j = Math.max(0, i - half); j <= Math.min(a.length - 1, i + half); j++) { s += a[j]; c++; } o[i] = s / c; }
        return o;
    }

    // ------------------------------------------------------------------ model
    static Map<String, Object> build(List<Seg> segs, List<Double> noise, List<Map<String, Object>> sources, boolean synthetic) {
        // duration: ms = a + b * log2(D/W + 1), least squares; urgency scales in log space
        int n = segs.size();
        // the optional Surprise! round is reported only; humanReactionDelay uses the user's measured sets below
        List<Double> rts = new ArrayList<>();
        for (Seg s : segs) if ("reaction".equals(s.kind) && s.onsetMs >= 100 && s.onsetMs <= 1500) rts.add(s.onsetMs);
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (Seg s : segs) { double id = log2(s.d / s.w + 1); sx += id; sy += s.dur; sxx += id * id; sxy += id * s.dur; }
        double b = (n * sxy - sx * sy) / Math.max(1e-9, n * sxx - sx * sx), a = (sy - b * sx) / n;
        if (b < 10) { b = Math.max(10, b); a = (sy - b * sx) / n; }
        a = Math.max(20, a);
        double[] res = new double[n]; double ru = 0, uu = 0, rm = 0, um = 0;
        for (int i = 0; i < n; i++) { Seg s = segs.get(i); res[i] = Math.log(s.dur / (a + b * log2(s.d / s.w + 1))); rm += res[i]; um += s.u; }
        rm /= n; um /= n;
        for (int i = 0; i < n; i++) { double du = segs.get(i).u - um; ru += du * (res[i] - rm); uu += du * du; }
        double slope = uu > 1e-6 ? ru / uu : -0.4;
        double sd = 0; for (int i = 0; i < n; i++) { double e = res[i] - rm - slope * (segs.get(i).u - um); sd += e * e; }
        sd = Math.sqrt(sd / n);
        // fold the mean residual back into the intercept so exp(0) is the median
        double corr = Math.exp(rm - slope * (um - 0.5)); a *= corr; b *= corr;


        double nsd = 0, nlag = 0, nvar = 0; int nc = 0;
        for (int i = 1; i < noise.size(); i++) { double x0 = noise.get(i - 1), x1 = noise.get(i); nvar += x1 * x1; nlag += x0 * x1; nc++; }
        nsd = nc > 0 ? Math.sqrt(nvar / nc) : 0.5; double rho = nc > 0 && nvar > 0 ? Math.max(0, Math.min(0.98, nlag / nvar)) : 0.8;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("format", "ostinato-human-mouse-profile/1");
        p.put("synthetic", synthetic);
        p.put("created", new Date().toString());
        p.put("units", map("distance", "mouse counts (raw, as Minecraft reads them)", "time", "ms"));
        p.put("sources", sources);
        p.put("reaction", reactionBlock(rts));
        p.put("duration", map("a", a, "b", b, "logSd", sd, "urgencySlope", slope, "minMs", 40.0, "maxMs", 3000.0));
        p.put("noise", map("sd", nsd, "rho", rho, "dtMs", GRID, "spectrum", spectrum(noise)));
        int subsSum = 0, os = 0; double pkSum = 0, cv = 0; for (Seg s : segs) { subsSum += s.subs; if (s.overshoot) os++; pkSum += s.peakAt; cv += s.maxPerp; }
        p.put("stats", map("movements", (double) n, "meanSubmovements", subsSum / (double) n, "overshootRate", os / (double) n,
                "meanPeakVelocityAt", pkSum / n, "meanMaxPerpOverD", cv / n));
        // keep a spread of templates
        List<Seg> keep = new ArrayList<>(segs);
        Collections.shuffle(keep, new Random(7));
        if (keep.size() > MAX_TEMPLATES) keep = keep.subList(0, MAX_TEMPLATES);
        List<Object> tl = new ArrayList<>();
        for (Seg s : keep) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("d", r3(s.d)); t.put("w", r3(s.w)); t.put("u", r3(s.u)); t.put("dur", r3(s.dur)); t.put("kind", s.kind == null ? "synthetic" : s.kind);
            t.put("along", r4(s.along)); t.put("perp", r4(s.perp));
            tl.add(t);
        }
        p.put("templates", tl);
        return p;
    }

    /** Ostinato: the user's measured tired reaction times (ms, 2026-10-14). */
    static final double[] TIRED_MS = {200, 214, 247, 290, 317, 350, 407};

    /** Ostinato: the user's measured peak reaction times (ms, 2026-10-14): 165 rare best, 173-185 typical fastest, 220 slowest. */
    static final double[] PEAK_MS = {165, 173, 177, 181, 185, 190, 198, 220};

    /** The surprise reaction block: the measured tired and peak sets and the attention walk. */
    static Map<String, Object> reactionBlock(List<Double> gameRts) {
        List<Object> tired = new ArrayList<>(), peak = new ArrayList<>();
        for (double v : TIRED_MS) tired.add(v);
        for (double v : PEAK_MS) peak.add(v);
        Map<String, Object> sets = new LinkedHashMap<>();
        sets.put("tired", map("samplesMs", tired, "estimated", false, "source", "user-measured while tired (200 fastest .. 407 distracted)", "boundsMs", r4list(190, 420)));
        sets.put("peak", map("samplesMs", peak, "estimated", false, "source", "user-measured at peak (165 rare best, 173-185 typical fastest, 220 slowest)", "boundsMs", r4list(165, 225)));
        Map<String, Object> b = map("model", "ostinato-attention-lognormal/1", "sets", sets,
                "attention", map("timescaleSec", 180.0, "stateLogSd", 0.08, "note", "slow OU walk 0 focused (fastest samples) .. 1 distracted (slowest)"),
                "autoFatigueMinutes", 180.0);
        if (!gameRts.isEmpty()) { List<Object> g = new ArrayList<>(); for (double v : gameRts) g.add(r3(v)); b.put("surpriseRoundMs", g); b.put("surpriseRoundNote", "from the optional Surprise! round, informational only"); }
        return b;
    }

    static List<Object> r4list(double... v) { List<Object> l = new ArrayList<>(); for (double x : v) l.add(x); return l; }

    /** Power of the tremor in a few bands (relative amplitude, counts), for inspection. */
    static List<Object> spectrum(List<Double> x) {
        int len = Math.min(x.size(), 1 << 14);
        List<Object> out = new ArrayList<>();
        double[] hz = {2, 4, 6, 8, 10, 12, 16, 20, 30, 40};
        for (double f : hz) {
            double re = 0, im = 0, w = 2 * Math.PI * f * GRID / 1000.0;
            for (int i = 0; i < len; i++) { re += x.get(i) * Math.cos(w * i); im += x.get(i) * Math.sin(w * i); }
            out.add(map("hz", f, "amp", len == 0 ? 0.0 : r4(Math.hypot(re, im) / Math.sqrt(len))));
        }
        return out;
    }

    // ------------------------------------------------------------------ synthetic profile
    static Map<String, Object> synthetic(Random r) {
        List<Seg> segs = new ArrayList<>(); List<Double> noise = new ArrayList<>();
        double a = 110, b = 105;
        for (int i = 0; i < 240; i++) {
            Seg s = new Seg();
            s.d = Math.exp(Math.log(15) + r.nextDouble() * (Math.log(4000) - Math.log(15)));
            s.w = Math.exp(Math.log(4) + r.nextDouble() * (Math.log(150) - Math.log(4)));
            s.u = new double[]{0.0, 0.5, 0.5, 1.0}[r.nextInt(4)];
            s.dur = (a + b * log2(s.d / s.w + 1)) * Math.exp(-0.35 * (s.u - 0.5) + 0.15 * r.nextGaussian());
            s.kind = "synthetic";
            // primary min-jerk submovement lands at g1 (under or over), then a corrective one to ~1 +- W/4
            double g1 = 0.92 + 0.12 * r.nextDouble(), split = 0.62 + 0.15 * r.nextDouble();
            double end = 1 + (r.nextGaussian() * 0.18) * s.w / s.d, bow = 0.04 * r.nextGaussian(), tilt = 0.01 * r.nextGaussian();
            s.along = new double[N + 1]; s.perp = new double[N + 1];
            for (int k = 0; k <= N; k++) {
                double t = k / (double) N;
                double p1 = minJerk(Math.min(1, t / split)) * g1;
                double p2 = t <= split ? 0 : minJerk((t - split) / (1 - split)) * (end - g1);
                s.along[k] = p1 + p2;
                s.perp[k] = bow * Math.sin(Math.PI * Math.min(1, t / split)) + tilt * minJerk(t);
            }
            s.subs = 2; s.overshoot = g1 > 1; s.peakAt = split * 0.5; s.maxPerp = Math.abs(bow);
            segs.add(s);
        }
        double x = 0; for (int i = 0; i < 4000; i++) { x = 0.8 * x + 0.6 * Math.sqrt(1 - 0.64) * r.nextGaussian(); noise.add(x); }
        Map<String, Object> p = build(segs, noise, new ArrayList<>(), true);
        p.put("note", "Synthetic example (min-jerk primary + corrective submovement). Replace with a profile fitted from your own recordings.");
        return p;
    }

    static double minJerk(double t) { return t * t * t * (10 - 15 * t + 6 * t * t); }

    // ------------------------------------------------------------------ helpers
    static double log2(double v) { return Math.log(v) / Math.log(2); }
    static double wrap(double a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }
    static double num(Object o, double d) { return o instanceof Number ? ((Number) o).doubleValue() : d; }
    static double mean(List<Double> l, double d) { if (l.isEmpty()) return d; double s = 0; for (double v : l) s += v; return s / l.size(); }
    static double sdev(List<Double> l, double d) { if (l.size() < 2) return d; double m = mean(l, 0), s = 0; for (double v : l) s += (v - m) * (v - m); return Math.sqrt(s / (l.size() - 1)); }
    static double r3(double v) { return Math.round(v * 1000) / 1000.0; }
    static List<Object> r4(double[] a) { List<Object> l = new ArrayList<>(); for (double v : a) l.add(Math.round(v * 10000) / 10000.0); return l; }
    static double r4(double v) { return Math.round(v * 10000) / 10000.0; }
    static Map<String, Object> map(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }

    /** Minimal JSON reader/writer, enough for the capture files. */
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        static Object parse(String s) { Json j = new Json(s); j.ws(); return j.val(); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object val() {
            char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = str(); ws(); i++; ws(); m.put(k, val()); ws(); if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { ws(); l.add(val()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }
        String str() {
            StringBuilder b = new StringBuilder(); i++;
            while (true) { char c = s.charAt(i++); if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(i++); switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break; case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break; default: b.append(e); } }
                else b.append(c); }
        }
        static String write(Object o) { StringBuilder b = new StringBuilder(); w(o, b, 0); return b.append('\n').toString(); }
        @SuppressWarnings("unchecked")
        static void w(Object o, StringBuilder b, int ind) {
            if (o == null) b.append("null");
            else if (o instanceof String) { b.append('"'); for (char c : ((String) o).toCharArray()) { if (c == '"' || c == '\\') b.append('\\').append(c); else if (c < 32) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } b.append('"'); }
            else if (o instanceof Double) { double d = (Double) o; b.append(Double.isFinite(d) ? (d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d)) : "0"); }
            else if (o instanceof Number || o instanceof Boolean) b.append(o);
            else if (o instanceof Map) { Map<String, Object> m = (Map<String, Object>) o; b.append("{"); int k = 0;
                for (Map.Entry<String, Object> e : m.entrySet()) { b.append(k++ > 0 ? "," : "").append('\n').append("  ".repeat(ind + 1)); w(e.getKey(), b, ind + 1); b.append(": "); w(e.getValue(), b, ind + 1); }
                b.append('\n').append("  ".repeat(ind)).append('}'); }
            else if (o instanceof List) { List<Object> l = (List<Object>) o; boolean flat = l.stream().allMatch(x -> x instanceof Number);
                b.append('['); for (int k = 0; k < l.size(); k++) { if (k > 0) b.append(flat ? ", " : ","); if (!flat) b.append('\n').append("  ".repeat(ind + 1)); w(l.get(k), b, ind + 1); }
                if (!flat && !l.isEmpty()) b.append('\n').append("  ".repeat(ind)); b.append(']'); }
            else b.append('"').append(o).append('"');
        }
    }
}
