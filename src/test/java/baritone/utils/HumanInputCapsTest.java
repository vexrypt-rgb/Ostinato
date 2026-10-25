/*
 * Ostinato: tests for the human-input gate. New file.
 */
package baritone.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class HumanInputCapsTest {

    @Test
    public void yawNeverMovesMoreThanCapPerTick() {
        for (float from = -720; from <= 720; from += 37) {
            for (float to = -720; to <= 720; to += 41) {
                float got = HumanInputCaps.capYaw(from, to, 120f);
                assertTrue(from + "->" + to, Math.abs(HumanInputCaps.wrap(got - from)) <= 120.001f);
            }
        }
    }

    @Test
    public void yawTurnsTheShortWayAndReachesSmallTargets() {
        assertEquals(10f, HumanInputCaps.capYaw(0, 10, 120f), 1e-4);
        assertEquals(-10f, HumanInputCaps.capYaw(0, 350, 120f), 1e-4); // short way round
        assertEquals(120f, HumanInputCaps.capYaw(0, 179, 120f), 1e-4); // snap to behind is capped
    }

    @Test
    public void pitchCappedAndInRange() {
        assertEquals(90f, HumanInputCaps.capPitch(80, 400, 90f), 1e-4);
        assertEquals(-90f, HumanInputCaps.capPitch(-80, -400, 90f), 1e-4);
        assertEquals(50f, HumanInputCaps.capPitch(-40, 89, 90f), 1e-4);
        assertEquals(30f, HumanInputCaps.capPitch(30, Float.NaN, 90f), 1e-4);
    }

    @Test
    public void noTeleportAimOnNonFinite() {
        assertEquals(15f, HumanInputCaps.capYaw(15, Float.NaN, 120f), 0);
        assertEquals(15f, HumanInputCaps.capYaw(15, Float.POSITIVE_INFINITY, 120f), 0);
    }

    @Test
    public void stickStopsAtFullTilt() {
        assertEquals(1f, HumanInputCaps.clampAxis(3f), 0);
        assertEquals(-1f, HumanInputCaps.clampAxis(-1.5f), 0);
        assertEquals(0f, HumanInputCaps.clampAxis(Float.NaN), 0);
        assertEquals(0.4f, HumanInputCaps.clampAxis(0.4f), 0);
    }
}
