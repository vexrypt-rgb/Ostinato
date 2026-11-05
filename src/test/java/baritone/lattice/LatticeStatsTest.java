/*
 * Ostinato: review fix. Known values for the Phase 03 statistics. Not upstream Baritone.
 */
package baritone.lattice;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LatticeStatsTest {
    @Test
    public void wilsonMatchesHandValues() {
        double[] all12 = LatticeStats.wilson(12, 12);
        assertEquals(0.7575, all12[0], 1e-3);
        assertEquals(1.0, all12[1], 1e-9);
        double[] most = LatticeStats.wilson(14, 16);
        assertEquals(0.640, most[0], 1e-3);
        assertEquals(0.965, most[1], 1e-3);
    }

    @Test
    public void fisherMatchesHandValues() {
        // 16/16 vs 14/16: the two failures land on one side with p = 2 * C(16,2)/C(32,2) = 0.4839.
        assertEquals(0.4839, LatticeStats.fisherTwoSided(16, 0, 14, 2), 1e-4);
        assertEquals(1.0, LatticeStats.fisherTwoSided(12, 0, 12, 0), 1e-12);
        // Classic tea-tasting table [[3,1],[1,3]]: two-sided p = 0.4857.
        assertEquals(0.4857, LatticeStats.fisherTwoSided(3, 1, 1, 3), 1e-4);
    }

    @Test
    public void percentileIsNearestRankAndBootstrapIsSeeded() {
        List<Double> xs = List.of(5.0, 1.0, 4.0, 2.0, 3.0);
        assertEquals(3.0, LatticeStats.percentile(xs, 0.5), 0);
        assertEquals(5.0, LatticeStats.percentile(xs, 0.95), 0);
        double[] a = LatticeStats.pairedMedianRatio(List.of(10, 20, 30), List.of(10, 20, 30));
        assertEquals(1.0, a[0], 1e-12);
        assertEquals(1.0, a[1], 1e-12);
        double[] b = LatticeStats.pairedMedianRatio(List.of(18, 20, 22, 16), List.of(17, 19, 14, 16));
        double[] c = LatticeStats.pairedMedianRatio(List.of(18, 20, 22, 16), List.of(17, 19, 14, 16));
        assertEquals(b[0], c[0], 0);
        assertTrue(b[0] <= b[1]);
    }
}
