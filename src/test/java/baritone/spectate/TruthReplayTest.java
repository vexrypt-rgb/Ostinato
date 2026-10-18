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
 * Ostinato: replays a SpectateBench truth.csv (-Dostinato.truth=path) through the decoder with every server tick
 * observed, to tell decoder-vs-server physics error apart from network sampling. Skipped without the property.
 */
public class TruthReplayTest {
    @Test
    public void replay() throws Exception {
        String p = System.getProperty("ostinato.truth");
        if (p == null || !Files.exists(Path.of(p))) return;
        int every = Integer.getInteger("ostinato.truth.every", 1);
        List<String> lines = Files.readAllLines(Path.of(p));
        String[] h = lines.get(0).split(",");
        Map<String, Integer> c = new HashMap<>();
        for (int i = 0; i < h.length; i++) c.put(h[i], i);
        Map<Integer, List<String[]>> by = new TreeMap<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] f = l.split(",");
            if (f.length == h.length) by.computeIfAbsent(Integer.parseInt(f[c.get("id")]), k -> new ArrayList<>()).add(f);
        }
        PlayerSim.World w = InputDecoderTest.flat();
        int n = 0, ok = 0, fOk = 0, sOk = 0, skipped = 0;
        Map<String, Integer> confusion = new TreeMap<>();
        for (List<String[]> rows : by.values()) {
            InputDecoder dec = new InputDecoder();
            int lastStick = -100;
            for (int k = 0; k < rows.size(); k++) {
                String[] r = rows.get(k);
                int st = Integer.parseInt(r[c.get("stick")]);
                if (st % every != 0) continue;
                InputDecoder.Obs o = new InputDecoder.Obs();
                o.x = InputDecoderTest.q(Double.parseDouble(r[c.get("x")]));
                o.y = InputDecoderTest.q(Double.parseDouble(r[c.get("y")]) + 63);
                o.z = InputDecoderTest.q(Double.parseDouble(r[c.get("z")]));
                String[] ry = Boolean.getBoolean("ostinato.truth.prevYaw") && k > 0 ? rows.get(k - 1) : r;
                o.yaw = Float.parseFloat(ry[c.get("yaw")]);
                o.pitch = Float.parseFloat(r[c.get("pitch")]);
                o.onGround = r[c.get("onGround")].equals("1");
                o.ticks = Math.max(1, st - lastStick);
                if (st - lastStick > 6 || o.ticks <= 0) dec.reset();
                lastStick = st;
                o.sprintFlag = r[c.get("sprint")].equals("1");
                o.sneakFlag = r[c.get("shift")].equals("1");
                o.usingFlag = r[c.get("using")].equals("1");
                int hurt = Integer.parseInt(r[c.get("hurtTime")]);
                o.special = hurt >= 9; // knockback this tick: no velocity packet in the truth, skip it
                InputDecoder.Decoded d = dec.step(w, o);
                if (d == null || d.reset || o.special) {
                    skipped++;
                    continue;
                }
                double full = 0.98 * (o.sneakFlag ? 0.3 : 1) * (o.usingFlag ? 0.2 : 1);
                int tf = key(Double.parseDouble(r[c.get("zza")]), full), ts = key(Double.parseDouble(r[c.get("xxa")]), full);
                n++;
                ok += (tf == d.forward && ts == d.strafe) ? 1 : 0;
                fOk += tf == d.forward ? 1 : 0;
                sOk += ts == d.strafe ? 1 : 0;
                confusion.merge(tf + "," + ts + "->" + d.forward + "," + d.strafe, 1, Integer::sum);
            }
        }
        System.out.printf("TRUTHREPLAY every %d: %d intervals, keys %.3f fwd %.3f str %.3f, %d skipped%n", every, n, ok / (double) n, fOk / (double) n, sOk / (double) n, skipped);
        confusion.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12).forEach(e -> System.out.println("TRUTHREPLAY  " + e));
    }

    static int key(double v, double full) {
        return v > InputDecoder.KEY * full ? 1 : v < -InputDecoder.KEY * full ? -1 : 0;
    }
}
