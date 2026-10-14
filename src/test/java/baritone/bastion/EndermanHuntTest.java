package baritone.bastion;

import org.junit.Test;
import static org.junit.Assert.*;

public class EndermanHuntTest {
    @Test public void onlyWhenTradingImpossible() {
        assertTrue(EndermanHunt.go("auto", OutsideTrade.Mode.INSIDE, false, 100, 2, 12));
        assertFalse(EndermanHunt.go("auto", OutsideTrade.Mode.INSIDE, true, 100, 2, 12));
        assertFalse(EndermanHunt.go("auto", OutsideTrade.Mode.WASTES, false, 100, 2, 12));
        assertFalse(EndermanHunt.go("auto", OutsideTrade.Mode.INSIDE, false, 900, 2, 12));
    }
    @Test public void pearlTargetStops() { assertFalse(EndermanHunt.go("force", OutsideTrade.Mode.INSIDE, false, 10, 12, 12)); }
    @Test public void offIsOff() { assertFalse(EndermanHunt.go("off", OutsideTrade.Mode.INSIDE, false, 10, 0, 12)); }
    @Test public void gapNeedsCeiling() {
        assertTrue(EndermanHunt.safeGap(true, true, true, true, false));
        assertFalse(EndermanHunt.safeGap(true, true, true, false, false));
        assertFalse(EndermanHunt.safeGap(true, true, true, true, true));
    }
    @Test public void oneAtATime() { assertTrue(EndermanHunt.provoke(10, 0)); assertFalse(EndermanHunt.provoke(10, 1)); assertFalse(EndermanHunt.provoke(30, 0)); }
}
