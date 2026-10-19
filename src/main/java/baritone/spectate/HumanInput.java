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

package baritone.spectate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ostinato: timings and cursor paths for server interactions that look like a person at the keyboard: the cursor
 * travels to a menu slot the way {@code LookBehavior}'s hand turns (speed ramps up, closes most of the gap each step,
 * carries a little past, a slight wrist arc, a tremor near the end), hovers, then clicks; chat is typed one key at a
 * time. Pure: loads without the game, so the shapes are unit tested.
 */
public final class HumanInput {
    private HumanInput() {}

    /** One step of the cursor path every this many milliseconds. */
    public static final int STEP_MS = 10;

    /** A uniform-ish draw in [lo, hi] (triangular, so the middle is likelier than the ends). */
    public static long between(Random r, long lo, long hi) {
        if (hi <= lo) return lo;
        double u = (r.nextDouble() + r.nextDouble()) / 2;
        return lo + Math.round(u * (hi - lo));
    }

    /**
     * Cursor points {@code {ms, x, y}} from (x0, y0) to (x1, y1), in window pixels, one every {@link #STEP_MS}. The
     * hand model of LookBehavior.humanStep in two dimensions: limited acceleration that scales with the distance still
     * to go, most of the gap closed each step, momentum past the target on long moves, a bowed path, a small tremor
     * near the end; the last point is the target itself.
     */
    public static List<double[]> cursorPath(double x0, double y0, double x1, double y1, Random r) {
        List<double[]> out = new ArrayList<>();
        double dx = x1 - x0, dy = y1 - y0, dist = Math.hypot(dx, dy);
        // a bowed path: the wrist arcs to one side, up to 12% of the distance
        double bow = (r.nextDouble() * 2 - 1) * 0.12 * dist;
        double nx = dist < 1e-6 ? 0 : -dy / dist, ny = dist < 1e-6 ? 0 : dx / dist;
        double mx = (x0 + x1) / 2 + nx * bow, my = (y0 + y1) / 2 + ny * bow;
        double x = x0, y = y0, vx = 0, vy = 0;
        double gain = 0.16 + r.nextDouble() * 0.08; // share of the gap closed per step
        int t = 0;
        for (int i = 0; i < 400; i++) {
            t += STEP_MS;
            double done = dist < 1e-6 ? 1 : 1 - Math.hypot(x1 - x, y1 - y) / dist;
            // aim at the bow's midpoint for the first half, then the slot
            double ax = done < 0.45 ? mx : x1, ay = done < 0.45 ? my : y1;
            double gx = ax - x, gy = ay - y, gap = Math.hypot(gx, gy);
            double wantX = gx * gain, wantY = gy * gain;
            double acc = 2 + gap * 0.08;
            vx += clamp(wantX - vx, -acc, acc);
            vy += clamp(wantY - vy, -acc, acc);
            if (gap > 120) { // a long move carries on a little past
                vx += vx * 0.08;
                vy += vy * 0.08;
            }
            x += vx;
            y += vy;
            double left = Math.hypot(x1 - x, y1 - y);
            if (left < 12) { // tremor of under a pixel near the end
                x += r.nextGaussian() * 0.35;
                y += r.nextGaussian() * 0.35;
            }
            out.add(new double[]{t, x, y});
            if (left < 1.2 && Math.hypot(vx, vy) < 1.5) break;
        }
        out.add(new double[]{t + STEP_MS, x1, y1});
        return out;
    }

    /** Per-key delays (ms) for typing {@code text}: each in [lo, hi], with a longer pause now and then. */
    public static long[] keyDelays(String text, Random r, long lo, long hi) {
        long[] out = new long[text.length()];
        for (int i = 0; i < out.length; i++) {
            long d = between(r, lo, hi);
            char c = text.charAt(i);
            if (c == ' ' || i > 0 && Character.isUpperCase(c) != Character.isUpperCase(text.charAt(i - 1))) d += between(r, 0, 60);
            if (r.nextDouble() < 0.06) d += between(r, 250, 650); // a hesitation
            out[i] = d;
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
