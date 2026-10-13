package baritone.bastion;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LavaFlowTest {
    /** flat floor at y=0, a source at (0,1,0), optional pit at x=3 */
    static LavaFlow.Grid flat(boolean pit) {
        return new LavaFlow.Grid() {
            public int lava(int x, int y, int z) { return x == 0 && y == 1 && z == 0 ? 0 : -1; }
            public boolean open(int x, int y, int z) { if (pit && x == 3 && z == 0 && y == 0) return true; return y >= 1; }
        };
    }

    @Test public void spreadsSevenAtTenTicksPerStep() {
        Map<Long, Integer> f = LavaFlow.forecast(flat(false), 0, 1, 0, 2, 1000);
        assertEquals(Integer.valueOf(10), f.get(LavaFlow.key(1, 1, 0)));
        assertEquals(Integer.valueOf(70), f.get(LavaFlow.key(7, 1, 0)));
        assertNull(f.get(LavaFlow.key(8, 1, 0)));
    }

    @Test public void horizonLimits() {
        Map<Long, Integer> f = LavaFlow.forecast(flat(false), 0, 1, 0, 2, 30);
        assertNotNull(f.get(LavaFlow.key(3, 1, 0)));
    }

    @Test public void fallsFirstIntoAPit() {
        Map<Long, Integer> f = LavaFlow.forecast(flat(true), 0, 1, 0, 2, 1000);
        assertEquals(Integer.valueOf(40), f.get(LavaFlow.key(3, 0, 0)));
    }

    @Test public void wallsBlock() {
        LavaFlow.Grid g = new LavaFlow.Grid() {
            public int lava(int x, int y, int z) { return x == 0 && y == 1 && z == 0 ? 0 : -1; }
            public boolean open(int x, int y, int z) { return y >= 1 && x != 1; }
        };
        Map<Long, Integer> f = LavaFlow.forecast(g, 0, 1, 0, 2, 1000);
        assertNull(f.get(LavaFlow.key(2, 1, 0)));
        assertNotNull(f.get(LavaFlow.key(-3, 1, 0)));
    }
    @Test public void waterIsFaster() {
        Map<Long, Integer> f = LavaFlow.forecast(flat(false), 0, 1, 0, 2, 1000, LavaFlow.WATER_RUN, LavaFlow.WATER_STEP_TICKS);
        assertEquals(Integer.valueOf(35), f.get(LavaFlow.key(7, 1, 0)));
    }

    @Test public void pushesTowardDrop() {
        // water at x=0..2, a deadly drop at x=3: cell x=2 pushes toward it, x=0 does not
        assertTrue(EdgeCost.pushesToward(true, true));
        assertFalse(EdgeCost.pushesToward(true, false));
        assertFalse(EdgeCost.pushesToward(false, true));
    }
}
