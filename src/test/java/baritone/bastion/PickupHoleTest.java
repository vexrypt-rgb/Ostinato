package baritone.bastion;

import org.junit.Test;

import static org.junit.Assert.*;

public class PickupHoleTest {
    @Test
    public void nearbySiteIsInReach() {
        assertTrue(BastionPriors.pickupInReach(1, 0, -1));
        assertTrue(BastionPriors.pickupInReach(3, -2, 4));
    }

    @Test
    public void farOrDeepSiteIsNot() {
        // s1161-b03: site 572,52,-833 with the bot at 566,73,-841
        assertFalse(BastionPriors.pickupInReach(6, -21, 8));
        assertFalse(BastionPriors.pickupInReach(6, 0, 0));
    }
}
