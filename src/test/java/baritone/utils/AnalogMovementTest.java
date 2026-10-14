/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.utils.AnalogMovement.Mode;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: the analogMovement gate and the vanilla 26.3 input chain it mirrors. */
public class AnalogMovementTest {

    @Test
    public void serverCheckDisablesSingleplayerOnly() {
        assertTrue(AnalogMovement.allowed(Mode.SINGLEPLAYER_ONLY, true));   // single-player / LAN host
        assertFalse(AnalogMovement.allowed(Mode.SINGLEPLAYER_ONLY, false)); // remote server
        assertFalse(AnalogMovement.allowed(Mode.OFF, true));
        assertFalse(AnalogMovement.allowed(Mode.OFF, false));
        assertTrue(AnalogMovement.allowed(Mode.ALWAYS, false));
    }

    @Test
    public void settingParsesAndDefaultsSafe() {
        assertEquals(Mode.SINGLEPLAYER_ONLY, Mode.parse("singleplayer_only"));
        assertEquals(Mode.SINGLEPLAYER_ONLY, Mode.parse("singleplayer-only"));
        assertEquals(Mode.ALWAYS, Mode.parse("ALWAYS"));
        assertEquals(Mode.OFF, Mode.parse("off"));
        assertEquals(Mode.OFF, Mode.parse("nonsense"));
        assertEquals(Mode.OFF, Mode.parse(null));
        assertEquals(Mode.SINGLEPLAYER_ONLY, Mode.parse(new baritone.api.Settings().analogMovement.defaultValue));
    }

    @Test
    public void vanillaModifyMatchesLocalPlayer() {
        float[] f = AnalogMovement.vanillaModify(0, 1, 1);
        assertEquals(0.98f, f[1], 1e-6);
        assertEquals(0f, f[0], 1e-6);
        // KeyboardInput normalises W+A to (0.707, 0.707); the square stretch takes it back to length 1
        float k = (float) Math.sqrt(0.5);
        f = AnalogMovement.vanillaModify(k, k, 1);
        assertEquals(1.0, Math.hypot(f[0], f[1]), 1e-6);
        // half stick diagonal: 0.5 * 0.98 * sqrt(2)
        f = AnalogMovement.vanillaModify(k / 2, k / 2, 1);
        assertEquals(0.49 * Math.sqrt(2), Math.hypot(f[0], f[1]), 1e-5);
        // crouching: SNEAKING_SPEED 0.3 on top of 0.98
        f = AnalogMovement.vanillaModify(0, 1, 0.3f);
        assertEquals(0.294f, f[1], 1e-6);
        assertArrayEquals(new float[]{0, 0}, AnalogMovement.vanillaModify(0, 0, 1), 0);
    }

    @Test
    public void moveVectorLimitsNotNormalises() {
        float[] v = AnalogMovement.moveVector(0.4f, 0.3f);
        assertEquals(0.3f, v[0], 1e-7);
        assertEquals(0.4f, v[1], 1e-7);
        v = AnalogMovement.moveVector(1, 1);
        assertEquals(1.0, Math.hypot(v[0], v[1]), 1e-6);
        v = AnalogMovement.moveVector(3, Float.NaN);
        assertEquals(1f, v[1], 0);
        assertEquals(0f, v[0], 0);
    }

    @Test
    public void sprintNeedsOnlyForwardImpulse() {
        assertTrue(AnalogMovement.hasForwardImpulse(0.01f));
        assertTrue(AnalogMovement.hasForwardImpulse(0.5f));
        assertFalse(AnalogMovement.hasForwardImpulse(0f));
        assertFalse(AnalogMovement.hasForwardImpulse(-0.5f));
    }
}
