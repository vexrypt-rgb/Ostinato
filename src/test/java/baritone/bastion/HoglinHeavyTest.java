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
}
