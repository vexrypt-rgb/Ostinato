package baritone.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class ExperimentalMovementTest {

    @Test
    public void fallDamageIsBlocksPastSafeFall() {
        assertEquals(0, ExperimentalMovement.fallDamage(3));
        assertEquals(1, ExperimentalMovement.fallDamage(4));
        assertEquals(20, ExperimentalMovement.fallDamage(ExperimentalMovement.MAX_HURT_FALL));
    }

    @Test
    public void affordFallKeepsMinHealth() {
        assertTrue(ExperimentalMovement.canAffordFall(20, 8, 12));
        assertFalse(ExperimentalMovement.canAffordFall(20, 9, 12));
    }
}
