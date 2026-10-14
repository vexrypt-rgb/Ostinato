package baritone.bastion;

import org.junit.Test;

import static org.junit.Assert.*;

public class HoglinHeavyTest {
    @Test
    public void packIsHeavy() {
        assertTrue(BastionPriors.hoglinsHeavy(2, 20)); // s1161-b04
    }

    @Test
    public void loneHoglinOnlyWhenHurt() {
        assertFalse(BastionPriors.hoglinsHeavy(1, 20));
        assertTrue(BastionPriors.hoglinsHeavy(1, 10));
        assertFalse(BastionPriors.hoglinsHeavy(0, 2));
    }

    @Test
    public void hoglinsNeverMakeUsLeave() {
        assertFalse(BastionPriors.leaveOnHeavy(true)); // s1161-b05
        assertTrue(BastionPriors.leaveOnHeavy(false));
    }

    @Test
    public void gappleWhenLowWithHoglinOnUs() {
        assertTrue(BastionPriors.eatGapple(9, false, 1)); // s1161-b06
        assertFalse(BastionPriors.eatGapple(9, true, 1));
        assertFalse(BastionPriors.eatGapple(15, false, 2));
        assertFalse(BastionPriors.eatGapple(5, false, 0));
    }

    @Test
    public void wallInAgainstHoglins() {
        assertTrue(BastionPriors.shelterFromHoglins(2, 20)); // s1161-b08
        assertTrue(BastionPriors.shelterFromHoglins(1, 12));
        assertFalse(BastionPriors.shelterFromHoglins(1, 18));
        assertFalse(BastionPriors.shelterFromHoglins(0, 4));
    }

    @Test
    public void perchOverWallsForHoglinsWithHeadroom() { // s1161-b09: 3 min walled in, never fought
        assertTrue(BastionPriors.hoglinPerch(true, true));
        assertFalse(BastionPriors.hoglinPerch(false, true));
        assertEquals(1.9, BastionPriors.perchHeight(true), 0);
        assertEquals(3.7, BastionPriors.perchHeight(false), 0);
    }
}
