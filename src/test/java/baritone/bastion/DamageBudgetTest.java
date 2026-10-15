package baritone.bastion;

import org.junit.Test;
import static org.junit.Assert.*;

public class DamageBudgetTest {
    @Test public void fullHpRawBruteHitBreaksFloor() { assertFalse(DamageBudget.allow(20, 13, 1, false, false, false, 20)); assertTrue(DamageBudget.allow(20, 11, 1, false, false, false, 20)); }
    @Test public void twoBruteHitsAreTooMany() { assertFalse(DamageBudget.allow(20, 13, 2, false, false, false, 20)); }
    @Test public void twoPiglinHitsAreFine() { assertTrue(DamageBudget.allow(20, 5, 2, false, false, false, 20)); }
    @Test public void hazardsVeto() {
        assertFalse(DamageBudget.allow(20, 5, 1, true, false, false, 20));
        assertFalse(DamageBudget.allow(20, 5, 1, false, true, false, 20));
        assertFalse(DamageBudget.allow(20, 5, 1, false, false, true, 20));
        assertFalse(DamageBudget.allow(20, 5, 1, false, false, false, 10));
    }
    @Test public void abortBelowFloor() { assertTrue(DamageBudget.abort(7, 5)); assertFalse(DamageBudget.abort(20, 13)); assertTrue(DamageBudget.abort(12, 13)); }
}
