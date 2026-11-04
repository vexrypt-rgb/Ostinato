package baritone.process;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DigStallMathTest {

    @Test
    public void holdsASwingLongEnoughToFinishTheBlock() {
        assertEquals(41, DigStallMath.budgetTicks(1)); // instant break still gets the +40 look-settle window
        int mid = DigStallMath.budgetTicks(0.05); // about 20 ticks of mining plus settle
        assertTrue(mid >= 55 && mid <= 65);
        int hand = DigStallMath.budgetTicks(1.0 / 150.0); // bare hand on stone is 7.5s; the old 6s give-up reset the crack
        assertTrue(hand >= 180 && hand <= 200);
        assertEquals(400, DigStallMath.budgetTicks(0.001)); // cap, then another face
        assertEquals(160, DigStallMath.budgetTicks(0));
        assertEquals(160, DigStallMath.budgetTicks(Double.POSITIVE_INFINITY));
    }
}
