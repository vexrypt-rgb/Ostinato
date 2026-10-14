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

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

/** Ostinato: surprise reaction times follow the user's measured sets, bounds, energy and a slow attention drift. */
public class ReactionModelTest {

    static double[] run(ReactionModel m, ReactionModel.Energy e, int n, double stepSec, double sessionSec, long seed) {
        Random r = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = m.sample(e, i * stepSec, sessionSec, r);
        return out;
    }

    @Test
    public void tiredSamplesStayInTheMeasuredBoundsAndMatchTheData() {
        double[] s = run(ReactionModel.defaults(), ReactionModel.Energy.TIRED, 20000, 20, 0, 1);
        Arrays.sort(s);
        assertTrue(s[0] >= 190 && s[s.length - 1] <= 420);
        double median = s[s.length / 2];
        // measured median 290 ms; the attention walk centers between focused (~207) and distracted (~378)
        assertEquals(285, median, 25);
        // the spread covers the measured range: fast and slow tails both reached
        assertTrue(s[s.length / 20] < 225);
        assertTrue(s[s.length * 19 / 20] > 345);
        // right-skewed like the data (mean above median in log-normal fashion, or at least not left-skewed)
        double mean = Arrays.stream(s).average().orElse(0);
        assertTrue(mean >= median - 5);
    }

    @Test
    public void attentionDriftsSlowly() {
        ReactionModel m = ReactionModel.defaults();
        Random r = new Random(2);
        double sumNear = 0, sumFar = 0;
        int n = 4000;
        double t = 0;
        for (int i = 0; i < n; i++) {
            double a0 = attentionAt(m, t, r);
            double a1 = attentionAt(m, t + 2, r);      // a surprise two seconds later
            double a2 = attentionAt(m, t + 1802, r);   // half an hour later
            sumNear += Math.abs(a1 - a0);
            sumFar += Math.abs(a2 - a1);
            t += 3600 * 3;
        }
        assertTrue("near " + sumNear / n + " far " + sumFar / n, sumNear / n * 4 < sumFar / n);
    }

    static double attentionAt(ReactionModel m, double t, Random r) {
        m.drift(t, r);
        return m.attention();
    }

    @Test
    public void peakIsMeasuredFastAndAutoDriftsTowardTired() {
        ReactionModel m = ReactionModel.defaults();
        assertFalse(m.peak.estimated);
        assertFalse(m.tired.estimated);
        double[] peak = run(ReactionModel.defaults(), ReactionModel.Energy.PEAK, 20000, 20, 0, 3);
        double[] tired = run(ReactionModel.defaults(), ReactionModel.Energy.TIRED, 20000, 20, 0, 3);
        Arrays.sort(peak);
        Arrays.sort(tired);
        // peak: never under the 165 ms best, the floor is rare, the bulk is 175..200, the tail reaches ~220
        assertTrue(peak[0] >= 165 && peak[peak.length - 1] <= 225);
        assertEquals(186, peak[peak.length / 2], 8);
        int nearFloor = 0;
        for (double v : peak) if (v < 170) nearFloor++;
        assertTrue("near floor " + nearFloor, nearFloor < peak.length / 10);
        assertTrue(peak[peak.length * 19 / 20] > 200);
        assertTrue(peak[peak.length / 2] < tired[tired.length / 2] - 60);
        assertEquals(0, m.fatigue(ReactionModel.Energy.AUTO, 0), 0);
        assertEquals(0.5, m.fatigue(ReactionModel.Energy.AUTO, 90 * 60), 1e-9);
        assertEquals(1, m.fatigue(ReactionModel.Energy.AUTO, 10 * 3600), 0);
        assertEquals(ReactionModel.Energy.TIRED, ReactionModel.Energy.parse("whatever"));
        assertEquals(ReactionModel.Energy.AUTO, ReactionModel.Energy.parse(" Auto "));
    }
}
