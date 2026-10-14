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

package baritone.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Ostinato: runs tools/human-mouse/HumanMouseFit.java on a synthetic capture session with known timing, small
 * tremor, one urgency level and some moves onto targets as close as they are wide, and checks the fit.
 */
public class HumanMouseFitTest {

    static double minJerk(double t) {
        return t * t * t * (10 - 15 * t + 6 * t * t);
    }

    /** A fitts round in the capture file format; moves take 100 + 120 * log2(D/W + 1) ms; every 10th is a too-close target. */
    static String session(Random r) {
        StringBuilder t = new StringBuilder(), x = new StringBuilder(), y = new StringBuilder(), trials = new StringBuilder();
        double time = 0, cx = 600, cy = 400;
        double tremA = 0, tremB = 0;
        for (int k = 0; k < 150; k++) {
            boolean close = k % 10 == 0;
            double rad = 6 + r.nextDouble() * 25, d = close ? rad * 1.2 : Math.exp(Math.log(60) + r.nextDouble() * Math.log(900 / 60.0)), a = r.nextDouble() * Math.PI * 2;
            double tx = cx + Math.cos(a) * d, ty = cy + Math.sin(a) * d, tOn = time;
            double dur = 100 + 120 * Math.log(d / (2 * rad) + 1) / Math.log(2);
            time += 200;
            double px = 0, py = 0; // counts sent so far in this move
            for (double q = 0; q <= dur + 150; q += 4) {
                double f = Math.min(1, q / dur), m = minJerk(f);
                tremA = 0.6 * tremA + 0.25 * r.nextGaussian();
                tremB = 0.6 * tremB + 0.25 * r.nextGaussian();
                double wantX = (tx - cx) * m + tremA, wantY = (ty - cy) * m + tremB;
                if (close) { wantX += 1.5 * d * Math.sin(f * Math.PI); }
                long sx = Math.round(wantX - px), sy = Math.round(wantY - py);
                if (sx == 0 && sy == 0) continue;
                if (t.length() > 0) { t.append(','); x.append(','); y.append(','); }
                t.append(time + q); x.append(sx); y.append(sy);
                px += sx; py += sy;
            }
            double tEnd = time + dur + 60;
            if (trials.length() > 0) trials.append(',');
            trials.append(String.format(java.util.Locale.ROOT, "{\"kind\":\"fitts\",\"tOn\":%.3f,\"from\":{\"x\":%.3f,\"y\":%.3f},\"target\":{\"x\":%.3f,\"y\":%.3f,\"r\":%.3f},\"urgency\":0.5,\"hit\":true,\"tEnd\":%.3f}",
                    tOn, cx, cy, tx, ty, rad, tEnd));
            cx += px;
            cy += py;
            time += dur + 300;
        }
        return "{\"format\":\"ostinato-mouse-capture/1\",\"sessionId\":\"test\",\"settings\":{},\"rounds\":[{\"task\":\"fitts\",\"mode\":\"lock\","
                + "\"settings\":{\"pageGainPxPerCount\":1,\"mcDegreesPerCount\":0.15,\"mouseDpi\":800},"
                + "\"samples\":{\"t\":[" + t + "],\"x\":[" + x + "],\"y\":[" + y + "]},\"trials\":[" + trials + "]}]}";
    }

    @Test
    public void fitsKnownTimingRejectsWandersAndKeepsTremorSmall() throws Exception {
        Path dir = Files.createTempDirectory("hmfit");
        Path in = dir.resolve("session.json"), out = dir.resolve("profile.json");
        Files.write(in, session(new Random(7)).getBytes(StandardCharsets.UTF_8));
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process p = new ProcessBuilder(java, "tools/human-mouse/HumanMouseFit.java", "-o", out.toString(), in.toString()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("fit timed out", p.waitFor(120, TimeUnit.SECONDS));
        assertEquals(log, 0, p.exitValue());
        JsonObject prof = JsonParser.parseString(new String(Files.readAllBytes(out), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject du = prof.getAsJsonObject("duration"), no = prof.getAsJsonObject("noise"), st = prof.getAsJsonObject("stats");
        // the 15 too-close targets are rejected as wanders, not kept as huge-curvature templates
        JsonObject rej = prof.getAsJsonObject("rejected");
        assertTrue(log, rej.has("fitts: target closer than its width") && rej.get("fitts: target closer than its width").getAsDouble() >= 12);
        assertEquals(log, 135, st.get("movements").getAsDouble(), 3);
        // a single urgency level: no slope is fitted from it, the default stays (and it's never positive)
        assertTrue(du.get("urgencySlope").getAsDouble() <= 0);
        // timing: the 5%-speed onset/offset trims the min-jerk ends, so the slope comes out a bit under 120
        double b = du.get("b").getAsDouble();
        assertTrue(log + " b=" + b, b > 70 && b < 135);
        // tremor of ~0.3 counts plus count rounding, not the movement itself
        assertTrue(log + " noise sd=" + no.get("sd").getAsDouble(), no.get("sd").getAsDouble() < 1.0);
        // movement time is onset to stop: the 200 ms before moving (reaction) and the dwell before the click are not in it
        assertEquals(log, 230, st.get("medianReactionMs").getAsDouble(), 60); // 200 ms still, then the min-jerk speed reaches 10%
        assertTrue(log + " a=" + du.get("a").getAsDouble(), du.get("a").getAsDouble() < 160);
        assertTrue(log, st.get("medianDwellMs").getAsDouble() > 30);
        // straight moves
        assertTrue(st.get("medianMaxPerpOverD").getAsDouble() < 0.05);
        assertTrue(log, log.contains("rejected"));
    }
}
