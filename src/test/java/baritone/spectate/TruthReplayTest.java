/*
 * Ostinato: new file. Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.pathing.kinematic.PlayerSim;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Ostinato: replays recorded per-tick movement with the true keys (-Dostinato.truth=file[,file...]: a SpectateBench
 * truth.csv of server bots, or selftruth.csv of our own client in a real fight) through the decoder the way a
 * spectator would see it: positions on the 1/4096 grid, every tick, every second tick, or the live mix (43% of
 * updates a tick early), with the network clock's arrival gap (sd 0.08 ticks) and burst count. Skipped without the
 * property. Prints accuracy and how well the confidence is calibrated.
 */
public class TruthReplayTest {
    @Test
    public void replay() throws Exception {
        String p = System.getProperty("ostinato.truth");
        if (p == null) return;
        Map<String, List<String[]>> by = new LinkedHashMap<>();
        Map<String, Integer> c = null;
        for (String file : p.split(",")) {
            if (!Files.exists(Path.of(file))) continue;
            List<String> lines = Files.readAllLines(Path.of(file));
            String[] h = lines.get(0).split(",");
            c = new HashMap<>();
            for (int i = 0; i < h.length; i++) c.put(h[i], i);
            for (String l : lines.subList(1, lines.size())) {
                String[] f = l.split(",");
                if (f.length == h.length) by.computeIfAbsent(file + "#" + f[c.get("id")] + "#" + f[c.get("round")], k -> new ArrayList<>()).add(f);
            }
        }
        if (c == null) return;
        String modes = System.getProperty("ostinato.truth.modes", "1,2,live");
        String grid = System.getProperty("ostinato.truth.grid");
        if (grid == null) {
            for (String mode : modes.split(",")) run(by, c, mode);
            return;
        }
        // calibration grid: temp x fitOk x fitScale x misfitBase, each "a|b|c"
        String[] g = grid.split(";");
        for (String t : g[0].split("\\|")) for (String fo : g[1].split("\\|")) for (String fs : g[2].split("\\|")) for (String mb : g[3].split("\\|")) {
            InputDecoder.KEY_TEMP = Double.parseDouble(t);
            InputDecoder.FIT_OK = Double.parseDouble(fo);
            InputDecoder.FIT_SCALE = Double.parseDouble(fs);
            InputDecoder.MISFIT_BASE = Double.parseDouble(mb);
            System.out.println("TRUTHREPLAY grid temp=" + t + " fitOk=" + fo + " fitScale=" + fs + " misfitBase=" + mb);
            for (String mode : modes.split(",")) run(by, c, mode);
        }
    }

    private static void run(Map<String, List<String[]>> by, Map<String, Integer> c, String mode) {
        PlayerSim.World w = InputDecoderTest.flat();
        Random rnd = new Random(7);
        int n = 0, ok = 0, fOk = 0, sOk = 0, skipped = 0, lenOk = 0, lenN = 0, changes = 0, steps = 0;
        double brier = 0;
        double[][] bins = new double[5][3];
        long work = 0;
        int dbg = 0;
        List<Double> resid = new ArrayList<>();
        for (List<String[]> rows : by.values()) {
            InputDecoder dec = new InputDecoder();
            int lastStick = Integer.MIN_VALUE / 2;
            int[] lastKey = null;
            double y0 = Double.MAX_VALUE; // the bench floors are flat: the lowest a player stands is the test world's y=0
            for (String[] r : rows) y0 = Math.min(y0, Double.parseDouble(r[c.get("y")]));
            double floorY = Math.floor(y0 + 1e-6);
            for (int k = 0; k < rows.size(); k++) {
                String[] r = rows.get(k);
                int st = Integer.parseInt(r[c.get("stick")]);
                int gap = st - lastStick;
                int want = switch (mode) {
                    case "1" -> 1;
                    case "2" -> 2;
                    default -> rnd.nextDouble() < 0.43 ? 1 : 2;
                };
                double full = 0.98 * (r[c.get("shift")].equals("1") ? 0.3 : 1) * (r[c.get("using")].equals("1") ? 0.2 : 1);
                int[] tk = {key(Double.parseDouble(r[c.get("zza")]), full), key(Double.parseDouble(r[c.get("xxa")]), full)};
                if (lastKey != null && !Arrays.equals(tk, lastKey)) changes++;
                steps++;
                lastKey = tk;
                if (gap < want && gap > 0) continue;
                InputDecoder.Obs o = new InputDecoder.Obs();
                o.x = InputDecoderTest.q(Double.parseDouble(r[c.get("x")]));
                o.y = InputDecoderTest.q(Double.parseDouble(r[c.get("y")]) - floorY);
                o.z = InputDecoderTest.q(Double.parseDouble(r[c.get("z")]));
                o.yaw = InputDecoderTest.qYaw(Float.parseFloat(r[c.get("yaw")]));
                o.pitch = Float.parseFloat(r[c.get("pitch")]);
                o.onGround = r[c.get("onGround")].equals("1");
                o.sprintFlag = r[c.get("sprint")].equals("1");
                o.sneakFlag = r[c.get("shift")].equals("1");
                o.usingFlag = r[c.get("using")].equals("1");
                if (gap > InputDecoder.MAX_TICKS || gap <= 0) dec.reset();
                o.dtRecv = gap + rnd.nextGaussian() * 0.08;
                o.bursts = gap;
                o.ticks = Math.max(1, InputDecoder.guessTicks(o, false));
                lastStick = st;
                int hurt = Integer.parseInt(r[c.get("hurtTime")]);
                // knockback: the truth has no velocity packet to hand over, so the interval it lands in is skipped
                boolean kbInside = false;
                for (int q = Math.max(0, k - gap + 1); q <= k; q++) {
                    kbInside |= Integer.parseInt(rows.get(q)[c.get("hurtTime")]) >= 9;
                    // standing on a block the bots placed: the flat test world has no such block, so skip it too
                    kbInside |= rows.get(q)[c.get("onGround")].equals("1") && Double.parseDouble(rows.get(q)[c.get("y")]) - floorY > 0.01;
                }
                o.special = kbInside || hurt >= 9;
                InputDecoder.Decoded d = dec.step(w, o);
                if (d == null || d.reset || o.special) {
                    skipped++;
                    continue;
                }
                work += d.work;
                boolean confWrong = d.confidence > 0.95 && !(tk[0] == d.forward && tk[1] == d.strafe);
                if (DEBUG > 0 && mode.equals("1") && (Boolean.getBoolean("ostinato.truth.debugConf") ? confWrong : d.residual > 0.01) && dbg++ < DEBUG) {
                    String[] pr = rows.get(k - 1);
                    System.out.printf(Locale.ROOT, "TRUTHDBG st=%d conf=%.3f res=%.4f (%.3f,%.3f,%.3f) jump=%s xxa/zza=%s/%s dec=%d,%d true=%d,%d spr=%s gnd=%s/%s ht=%d yaw=%s/%s v=%.3f,%.3f,%.3f%n", st, d.confidence, d.residual, d.rx, d.ry, d.rz, d.jump, r[c.get("xxa")], r[c.get("zza")], d.forward, d.strafe, tk[0], tk[1],
                            r[c.get("sprint")], pr[c.get("onGround")], r[c.get("onGround")], hurt, pr[c.get("yaw")], r[c.get("yaw")],
                            Double.parseDouble(r[c.get("x")]) - Double.parseDouble(pr[c.get("x")]), Double.parseDouble(r[c.get("y")]) - Double.parseDouble(pr[c.get("y")]),
                            Double.parseDouble(r[c.get("z")]) - Double.parseDouble(pr[c.get("z")]));
                }
                resid.add(d.residual);
                n++;
                boolean right = tk[0] == d.forward && tk[1] == d.strafe;
                ok += right ? 1 : 0;
                fOk += tk[0] == d.forward ? 1 : 0;
                sOk += tk[1] == d.strafe ? 1 : 0;
                lenN++;
                lenOk += d.ticks == gap ? 1 : 0;
                brier += Math.pow(d.confidence - (right ? 1 : 0), 2);
                int b = Math.min(4, (int) (d.confidence * 5));
                bins[b][0]++;
                bins[b][1] += right ? 1 : 0;
                bins[b][2] += d.confidence;
            }
        }
        Collections.sort(resid);
        if (!resid.isEmpty()) System.out.printf(Locale.ROOT, "TRUTHREPLAY   residual p10 %.5f p50 %.5f p90 %.5f%n", resid.get(resid.size() / 10), resid.get(resid.size() / 2), resid.get(resid.size() * 9 / 10));
        double ece = 0;
        StringBuilder sb = new StringBuilder();
        for (int b = 0; b < 5; b++) {
            ece += Math.abs(bins[b][1] - bins[b][2]) / Math.max(1, n);
            sb.append(String.format(Locale.ROOT, " [%.1f: n=%d conf %.2f right %.2f]", b / 5.0, (int) bins[b][0], bins[b][2] / Math.max(1, bins[b][0]), bins[b][1] / Math.max(1, bins[b][0])));
        }
        System.out.printf(Locale.ROOT, "TRUTHREPLAY %s%s: %d intervals, keys %.3f fwd %.3f str %.3f, length %.3f, %d skipped, key changes/tick %.3f, sim ticks %.0f, Brier %.3f ECE %.3f%n",
                mode, InputDecoder.CONTINUOUS ? " (continuous)" : "", n, ok / (double) n, fOk / (double) n, sOk / (double) n, lenOk / (double) Math.max(1, lenN), skipped,
                changes / (double) Math.max(1, steps), work / (double) Math.max(1, n), brier / Math.max(1, n), ece);
        System.out.println("TRUTHREPLAY   calibration" + sb);
    }

    static final int DEBUG = Integer.getInteger("ostinato.truth.debug", 0);

    static int key(double v, double full) {
        return v > InputDecoder.KEY * full ? 1 : v < -InputDecoder.KEY * full ? -1 : 0;
    }
}
