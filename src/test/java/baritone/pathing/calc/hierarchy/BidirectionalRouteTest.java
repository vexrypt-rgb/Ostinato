/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc.hierarchy;

import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.Terrain;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: the bidirectional route search must find a route of the same cost as the one-sided backward Dijkstra
 * ({@link CostToGoMap}) on every bench world, on the section and the coarse level, and the route it returns must be a
 * chain of real edges from the start's cell to the goal's whose costs add up to that cost.
 */
public class BidirectionalRouteTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void sameCostAsBackwardDijkstra() {
        int checked = 0;
        for (Terrain t : Terrain.values()) {
            CalculationContext ctx = PathBench.context(PathBench.world(t));
            SectionGraph sg = new SectionGraph();
            for (PathBench.Query q : PathBench.queries(t)) {
                if (q.length() == PathBench.Length.SHORT) continue;
                BlockPos gp = ((IGoalRenderPos) q.goal()).getGoalPos();
                for (LevelGraph g : new LevelGraph[]{sg, sg.coarse(2)}) {
                    int margin = g == sg ? 4 : 1;
                    CostToGoMap m = CostToGoMap.build(g, ctx, q.start().x, q.start().y, q.start().z, gp.getX(), gp.getY(), gp.getZ(), margin, Long.MAX_VALUE, null);
                    double want = m.costToGo(q.start().x, q.start().y, q.start().z);
                    BidirectionalRoute.Result r = BidirectionalRoute.search(g, ctx, q.start().x, q.start().y, q.start().z, gp.getX(), gp.getY(), gp.getZ(), margin, Long.MAX_VALUE,
                            null, null, 1);
                    String id = q.id() + " shift " + g.shift();
                    if (Double.isNaN(want)) {
                        assertNull(id, r);
                        continue;
                    }
                    assertNotNull(id, r);
                    assertEquals(id, want, r.cost(), 1e-6 * Math.max(1, want));
                    checkChain(id, g, ctx, r, q.start().x >> g.shift(), q.start().y >> g.shift(), q.start().z >> g.shift(),
                            gp.getX() >> g.shift(), gp.getY() >> g.shift(), gp.getZ() >> g.shift());
                    assertTrue(id + " settled " + r.expanded() + " vs " + m.expanded, r.expanded() <= m.expanded * 2);
                    checked++;
                }
            }
        }
        assertTrue("checked " + checked, checked >= 20);
    }

    private static void checkChain(String id, LevelGraph g, CalculationContext ctx, BidirectionalRoute.Result r, int sx, int sy, int sz, int gx, int gy, int gz) {
        int[] c = r.cells();
        assertArrayEquals(id + " start", new int[]{sx, sy, sz}, new int[]{c[0], c[1], c[2]});
        int n = c.length;
        assertArrayEquals(id + " goal", new int[]{gx, gy, gz}, new int[]{c[n - 3], c[n - 2], c[n - 1]});
        double sum = 0;
        for (int i = 0; i + 3 < n; i += 3) {
            int d = SectionGraph.dirIndex(c[i + 3] - c[i], c[i + 4] - c[i + 1], c[i + 5] - c[i + 2]);
            float e = g.outgoing(ctx, c[i], c[i + 1], c[i + 2])[d];
            assertTrue(id + " edge " + i / 3, Float.isFinite(e));
            sum += e;
        }
        assertEquals(id + " chain cost", r.cost(), sum, 1e-6 * Math.max(1, sum));
    }
}
