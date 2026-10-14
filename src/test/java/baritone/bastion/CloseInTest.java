package baritone.bastion;

import org.junit.Test;

import static org.junit.Assert.*;

public class CloseInTest {
    @Test
    public void keepsClosingInBriefly() {
        assertFalse(BastionPriors.giveUpCloseIn(0, 30, 12, false));
        assertFalse(BastionPriors.giveUpCloseIn(0, 90, 12, true));
    }

    @Test
    public void givesUpOnHiddenNearPiglin() {
        assertTrue(BastionPriors.giveUpCloseIn(0, 41, 6, false));
        assertFalse(BastionPriors.giveUpCloseIn(0, 41, 6, true));
    }

    @Test
    public void givesUpAfterLimit() {
        assertTrue(BastionPriors.giveUpCloseIn(100, 201, 20, true));
    }
}
