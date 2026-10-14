package baritone.bastion;

import org.junit.Test;

import static org.junit.Assert.*;

public class GoldSpendTest {
    @Test
    public void craftsOnlyWhenLowAndHoldingBlocks() {
        assertTrue(BastionPriors.shouldCraftIngots(3, 2, 20));
        assertFalse(BastionPriors.shouldCraftIngots(20, 2, 20));
        assertFalse(BastionPriors.shouldCraftIngots(3, 0, 20));
        assertFalse(BastionPriors.shouldCraftIngots(3, 2, 5));
    }

    @Test
    public void overtimeWhileGoldAndPearlsShort() {
        // s1161-b02: 7 of 8 pearls, 117 ingots' worth, 18 hp at 300 s
        assertTrue(BastionPriors.overtime(6001, 300, 7, 8, 117, 240, 18));
        assertFalse(BastionPriors.overtime(6001, 300, 8, 8, 117, 240, 18));
        assertFalse(BastionPriors.overtime(6001, 300, 7, 8, 3, 240, 18));
        assertFalse(BastionPriors.overtime(6001, 300, 7, 8, 117, 240, 8));
        assertFalse(BastionPriors.overtime(10801, 300, 7, 8, 117, 240, 18));
    }
}
