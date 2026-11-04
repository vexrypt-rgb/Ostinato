/*
 * Ostinato Legato: a route planned in one Legato mode is never replayed in another.
 */
package baritone.pathing;

import baritone.Baritone;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.Terrain;
import baritone.pathing.movement.CalculationContext;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class LegatoReuseTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void offAgain() {
        Baritone.settings().legato.value = false;
        Baritone.settings().legatoSmoothing.value = true;
        Baritone.settings().legatoSearch.value = false;
    }

    private static long stampFor(boolean legato, boolean smooth, boolean search) {
        Baritone.settings().legato.value = legato;
        Baritone.settings().legatoSmoothing.value = smooth;
        Baritone.settings().legatoSearch.value = search;
        CalculationContext ctx = PathBench.context(PathBench.world(Terrain.FLAT));
        return RouteReuse.stamp(ctx);
    }

    @Test
    public void modesHaveDistinctStamps() {
        long base = RouteReuse.stamp();
        if (base == Long.MIN_VALUE) {
            return; // a jump failure is still priced: nothing is stored at all
        }
        assertEquals(base, stampFor(false, true, true)); // master off: as before Legato
        Set<Long> seen = new HashSet<>();
        seen.add(stampFor(false, false, false));
        seen.add(stampFor(true, true, false));
        seen.add(stampFor(true, false, true));
        seen.add(stampFor(true, true, true));
        assertEquals(4, seen.size());
    }
}
