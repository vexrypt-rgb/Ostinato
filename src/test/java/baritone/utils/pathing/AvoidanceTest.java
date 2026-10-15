package baritone.utils.pathing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AvoidanceTest {

    @Test
    public void dangerIsTheCenterAndGonePastTheReach() {
        Avoidance creeper = new Avoidance(0, 0, 0, 8, 7, true);
        assertEquals(8, creeper.coefficient(0, 0, 0), 0.001);
        assertEquals(1, creeper.coefficient(7, 0, 0), 0.001);
        assertEquals(1, creeper.coefficient(8, 0, 0), 0.001);
        // 3 blocks out of a reach of 7: 1 + 7 * (1 - 3/7)^2
        assertEquals(1 + 7 * (4.0 / 7) * (4.0 / 7), creeper.coefficient(3, 0, 0), 0.001);
    }

    /** Spawners are painted once, flat, into the favor map. Mobs are not. */
    @Test
    public void aPaintedSphereIsFlatAndStopsAtTheRadius() {
        it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap map = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        map.defaultReturnValue(1);
        new Avoidance(0, 0, 0, 2, 2).applySpherical(map);
        assertEquals(2, map.get(baritone.api.utils.BetterBlockPos.longHash(0, 0, 0)), 0.001);
        assertEquals(2, map.get(baritone.api.utils.BetterBlockPos.longHash(2, 0, 0)), 0.001);
        assertEquals(1, map.get(baritone.api.utils.BetterBlockPos.longHash(3, 0, 0)), 0.001);
    }
}
