/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc.hierarchy;

import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.Terrain;
import baritone.pathing.movement.CalculationContext;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: the coarse (64-block) level over sections: edges exist on open ground and drop on invalidation. */
public class CoarseGraphTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void flatCoarseEdgesAndInvalidation() {
        CalculationContext ctx = PathBench.context(PathBench.world(Terrain.FLAT));
        SectionGraph sg = new SectionGraph();
        CoarseGraph cg = sg.coarse(2);
        assertEquals(6, cg.shift());
        assertSame(cg, sg.coarse(2));
        float[] e = cg.outgoing(ctx, 2, 1, 0);
        int east = SectionGraph.dirIndex(1, 0, 0);
        assertTrue("east edge on flat ground: " + e[east], Float.isFinite(e[east]));
        // a 64-block hop on flat ground costs at least walking 64 blocks' worth of sections, and not absurdly more
        assertTrue(e[east] > 20 && e[east] < 64 * 4.633 * 3);
        long before = cg.computedCells;
        assertSame(e, cg.outgoing(ctx, 2, 1, 0)); // cached
        sg.invalidateBlock(2 * 64 + 5, 70, 3); // inside coarse cell (2,1,0)
        assertNotSame(e, cg.outgoing(ctx, 2, 1, 0));
        assertEquals(before + 1, cg.computedCells);
    }
}
