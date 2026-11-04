package baritone.bombard;

import org.junit.Test;

import static org.junit.Assert.*;

public class TntMechanicsTest {
    @Test public void tinyClippedMovementDoesNotDriftAcrossTheFloor() {
        double[] next = TntMechanics.step(new double[]{0.5, 64, 0.5, 0.0001, 0, 0}, 64);
        assertEquals(0.5, next[0], 0);
        assertTrue(next[3] > 0); // vanilla keeps damping velocity despite suppressing the displacement
    }
    private static final double E = 1e-12;

    @Test
    public void freeAirTickIsGravityThenMoveThenDrag() {
        double[] s = TntMechanics.step(new double[]{0, 70, 0, 0.1, 0, -0.2});
        assertEquals(0.1, s[0], E);
        assertEquals(70 - 0.04, s[1], E); // moved by the velocity after gravity
        assertEquals(-0.2, s[2], E);
        assertEquals(0.1 * (double) 0.98f, s[3], E);
        assertEquals(-0.04 * (double) 0.98f, s[4], E);
        assertEquals(-0.2 * (double) 0.98f, s[5], E);
    }

    @Test
    public void groundDampingOnlyOnceLanded() {
        // above the floor: drag only
        double[] air = TntMechanics.step(new double[]{0, 70, 0, 0.1, 0, 0}, 64);
        assertEquals(0.1 * (double) 0.98f, air[3], E);
        // a fall that reaches the floor stops on it and is damped by the ground after the drag
        double[] land = TntMechanics.step(new double[]{0, 64.02, 0, 0.1, -0.1, 0}, 64);
        assertEquals(64, land[1], E);
        assertEquals(0, land[4], E);
        assertEquals(0.1 * (double) 0.98f * 0.7, land[3], E);
        // at rest on the floor it stays there
        double[] rest = TntMechanics.step(new double[]{0, 64, 0, 0, 0, 0}, 64);
        assertEquals(64, rest[1], E);
        assertEquals(0, rest[4], E);
        // rising off the floor is not a landing
        double[] up = TntMechanics.step(new double[]{0, 64, 0, 0.1, 0.2, 0}, 64);
        assertEquals(64.16, up[1], E);
        assertEquals(0.1 * (double) 0.98f, up[3], E);
    }

    @Test
    public void rodPullsTheHookPointTowardTheOwnersFeet() {
        double[] v = TntMechanics.rodPull(0, 64, 0, 10, 64, -5);
        assertEquals(1.0, v[0], E);
        assertEquals(0.1 * (64 - (64 + 0.98 * 0.8)), v[1], E);
        assertEquals(-0.5, v[2], E);
    }

    @Test
    public void spearPushIsAlongTheYawWithKnockbackOnTop() {
        double[] south = TntMechanics.spearPush(0, 0);
        assertEquals(0, south[0], E);
        assertEquals(0.1, south[1], E);
        assertEquals(0.4, south[2], E);
        double[] west = TntMechanics.spearPush(90, 0);
        assertEquals(-0.4, west[0], E);
        assertEquals(0, west[2], 1e-9);
        // knockback 1 (attribute + enchant): a second push of 1/2 along the same yaw, with its own 0.1 up
        double[] kb = TntMechanics.spearPush(0, 1);
        assertEquals(0.9, kb[2], E);
        assertEquals(0.2, kb[1], E);
        assertArrayEquals(south, TntMechanics.spearPush(0, -1), E);
    }

    @Test
    public void ticksLeftIsNeverUnderOne() {
        assertEquals(80, TntMechanics.ticksLeft(80));
        assertEquals(1, TntMechanics.ticksLeft(1));
        assertEquals(1, TntMechanics.ticksLeft(0));
        assertEquals(1, TntMechanics.ticksLeft(-3));
    }

    @Test
    public void chainLitIsTheBlastFuseRangeAtPriming() {
        assertTrue(TntMechanics.chainLit(10, 0));
        assertTrue(TntMechanics.chainLit(29, 0));
        assertFalse(TntMechanics.chainLit(9, 0));
        assertFalse(TntMechanics.chainLit(30, 0));
        assertTrue(TntMechanics.chainLit(5, 5)); // 10 at priming, seen 5 ticks on
        assertFalse(TntMechanics.chainLit(25, 5)); // 30 at priming: not a blast's
        assertFalse(TntMechanics.chainLit(15, -1));
        assertFalse(TntMechanics.chainLit(75, 1)); // a lit block's 80
    }

    @Test
    public void onlyVerifiedVersionsAreSupported() {
        TntMechanics.Support s = TntMechanics.support("26.3");
        assertTrue(s.rod() && s.spear() && s.chain() && s.any());
        for (String v : new String[]{"1.21.11", "26.2", "1.16.1", "", null}) {
            TntMechanics.Support o = TntMechanics.support(v);
            assertFalse(v, o.any());
            assertTrue(o.reason().contains("not verified"));
        }
    }

    @Test
    public void damageReachesTwiceThePower() {
        assertEquals(8, TntMechanics.damageRadius(4), E);
        assertEquals(6, TntMechanics.damageRadius(3), E);
        assertEquals(0.98 * 0.0625, TntMechanics.BLAST_Y, E);
    }
}
