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

package baritone.behavior.look;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

/** Ostinato: the shipped synthetic human mouse profile loads, lands on its targets and keeps the fitted timing. */
public class HumanMouseModelTest {

    static final Path EXAMPLE = Paths.get("src/launch/resources/assets/baritone/ostinato/human-mouse-example.json");

    static HumanMouseModel example() throws Exception {
        HumanMouseModel m = HumanMouseModel.load(EXAMPLE);
        assertTrue(m.isSynthetic());
        assertTrue(m.templateCount() > 50);
        return m;
    }

    @Test
    public void generatedMovesLandInsideTheTarget() throws Exception {
        HumanMouseModel m = example();
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            double d = Math.exp(Math.log(5) + r.nextDouble() * Math.log(5000 / 5.0)), a = r.nextDouble() * Math.PI * 2;
            double w = 2 + r.nextDouble() * 80, u = r.nextDouble();
            double dx = Math.cos(a) * d, dy = Math.sin(a) * d;
            HumanMouseModel.Movement mv = m.generate(dx, dy, w, u, r);
            double err = Math.hypot(mv.endX() - dx, mv.endY() - dy);
            assertTrue("missed by " + err + " (d=" + d + ", w=" + w + ")", err <= 0.4 * w + 1e-9);
            assertEquals(0, mv.x[0], 0);
            assertEquals(0, mv.y[0], 0);
            for (double v : mv.x) assertTrue(Double.isFinite(v));
        }
    }

    @Test
    public void movementIsGradualNotASnap() throws Exception {
        HumanMouseModel m = example();
        HumanMouseModel.Movement mv = m.generate(2000, 0, 20, 0.5, new Random(3));
        // no single 5 ms step covers a quarter of the move, and the middle of the move is mid-way
        for (int i = 1; i < mv.x.length; i++) assertTrue(Math.abs(mv.x[i] - mv.x[i - 1]) < 500);
        double mid = mv.at(mv.durationMs() / 2)[0];
        assertTrue("mid " + mid, mid > 300 && mid < 1900);
    }

    @Test
    public void timingMatchesTheProfile() throws Exception {
        HumanMouseModel m = example();
        Random r = new Random(2);
        int n = 1500;
        double[] mt = new double[n];
        for (int i = 0; i < n; i++) mt[i] = m.generate(600, 0, 30, 0.5, r).durationMs();
        Arrays.sort(mt);
        double want = m.medianDurationMs(600, 30, 0.5);
        assertEquals(want, mt[n / 2], want * 0.08);
        // the spread matches the fitted log-normal residual (interquartile ratio exp(2 * 0.674 * logSd))
        double iqr = Math.log(mt[3 * n / 4] / mt[n / 4]) / (2 * 0.674);
        assertEquals(m.durLogSd, iqr, 0.03);
        // duration grows with the index of difficulty and shrinks with urgency, as in the fit
        assertTrue(m.medianDurationMs(2000, 5, 0.5) > m.medianDurationMs(100, 50, 0.5));
        assertTrue(m.medianDurationMs(600, 30, 1.0) < m.medianDurationMs(600, 30, 0.0));
    }

    @Test
    public void surpriseReactionTimesMatchTheProfile() throws Exception {
        HumanMouseModel m = example();
        Random r = new Random(5);
        int n = 2001;
        double[] rt = new double[n];
        for (int i = 0; i < n; i++) rt[i] = m.sampleReactionMs(r);
        Arrays.sort(rt);
        assertEquals(m.medianReactionMs(), rt[n / 2], m.medianReactionMs() * 0.05);
        assertTrue(rt[0] >= 120 && rt[n - 1] <= 900);
    }

    @Test
    public void movementStartsImmediately() throws Exception {
        HumanMouseModel.Movement mv = example().generate(300, -200, 15, 0.8, new Random(4));
        assertTrue(Math.hypot(mv.x[3], mv.y[3]) > 0);
    }
}
