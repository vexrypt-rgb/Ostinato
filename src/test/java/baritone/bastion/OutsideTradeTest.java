package baritone.bastion;

import org.junit.Test;
import static org.junit.Assert.*;
import static baritone.bastion.OutsideTrade.Mode.*;

public class OutsideTradeTest {
    @Test public void freePiglinsStayInside() { assertEquals(INSIDE, OutsideTrade.choose("auto", true, false, 2, 5, 50)); }
    @Test public void guardedLuresOut() { assertEquals(LURE_OUT, OutsideTrade.choose("auto", true, false, 0, 3, 50)); }
    @Test public void oneGuardedGoesToWastes() { assertEquals(WASTES, OutsideTrade.choose("auto", true, false, 0, 1, 50)); }
    @Test public void farWastesStays() { assertEquals(INSIDE, OutsideTrade.choose("auto", true, false, 0, 1, 400)); }
    @Test public void beforeRouteEndsInside() { assertEquals(INSIDE, OutsideTrade.choose("auto", false, false, 0, 4, 50)); }
    @Test public void sufficientStops() { assertEquals(DONE, OutsideTrade.choose("force", true, true, 0, 4, 50)); }
    @Test public void offAndForce() {
        assertEquals(INSIDE, OutsideTrade.choose("off", true, false, 0, 4, 50));
        assertEquals(LURE_OUT, OutsideTrade.choose("force", true, false, 3, 0, 50));
    }
    @Test public void lureCountClamps() { assertEquals(4, OutsideTrade.lureCount(9)); assertEquals(1, OutsideTrade.lureCount(1)); }
    @Test public void outsideMargin() { assertTrue(OutsideTrade.outside(40, 0, 30)); assertFalse(OutsideTrade.outside(35, 0, 30)); }
}
