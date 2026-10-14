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

package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: PlayerSim's analog input against vanilla 26.3, and the key path left exactly as it was. */
public class PlayerSimAnalogTest {

    /** Flat floor at y=0 with a two-block gap at x=6..7. */
    private static final PlayerSim.World W = new PlayerSim.World() {
        @Override
        public void collect(double a, double b, double c, double d, double e, double f, List<double[]> out) {
            for (int x = PlayerSim.floor(a); x <= PlayerSim.floor(d); x++)
                for (int y = PlayerSim.floor(b); y <= PlayerSim.floor(e); y++)
                    for (int z = PlayerSim.floor(c); z <= PlayerSim.floor(f); z++)
                        if (y == -1 && !(x >= 6 && x <= 7)) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }
    };

    private static PlayerSim start() {
        PlayerSim s = new PlayerSim(W);
        s.x = 0.5; s.z = 0.5; s.onGround = true;
        return s;
    }

    /**
     * Bit-exact trace of the key path recorded from PlayerSim before analog input was added (parkour-263 f6ff8068):
     * forward/back/none, A/D, sprint and jump mixed. Analog off must not move a single bit.
     */
    private static final long[][] GOLDEN = {
            {0x4001bd0609059973L, 0x3fe81a36e2eb1c42L, 0x3ff6a4979d6163faL, 0x3fc776597fc53a10L, 0x3fa42606b76ed2adL},
            {0x4015264c6092d962L, 0x3ff2a862345297fcL, 0x400204196f70c578L, 0x3fc7dc6435c0ea4bL, 0x3fb4538f3ae76f2eL},
            {0x4020b80a20e564c5L, 0xc025689e568200d2L, 0x400b91a753d186f0L, 0x3fc281a76c587bb8L, 0x3fb10ad940f4db3eL},
            {0x4025b22d8da37d50L, 0xc04658265638758eL, 0x40113ddeb87c7ef3L, 0x3fc2cddd43150f00L, 0x3fb2ae9ca4f75bfdL},
            {0x402a51772c3b1d02L, 0xc0575bee4ad20e14L, 0x40143519602b58c1L, 0x3fb85357b472bc4cL, 0x3fa4db659e8e14ffL},
            {0x402e756ddf009c42L, 0xc063014a779897f4L, 0x401758c930a11c63L, 0x3fb022d19991a12bL, 0x3f94df5a685868fdL},
    };

    private static int keyIn(int t) {
        return t % 17 < 12 ? 1 : (t % 17 < 14 ? 0 : -1);
    }

    private static int keyStrafe(int t) {
        return Math.max(-1, Math.min(1, t % 7 - 3));
    }

    @Test
    public void keyPathUnchanged() {
        PlayerSim s = start();
        int row = 0;
        for (int t = 0; t < 120; t++) {
            s.tick((float) (-90 + (t % 11) * 3.7), keyIn(t), keyStrafe(t), t % 5 != 0, t % 9 == 0);
            if (t % 20 == 19) {
                long[] g = GOLDEN[row++];
                assertEquals(g[0], Double.doubleToLongBits(s.x));
                assertEquals(g[1], Double.doubleToLongBits(s.y));
                assertEquals(g[2], Double.doubleToLongBits(s.z));
                assertEquals(g[3], Double.doubleToLongBits(s.vx));
                assertEquals(g[4], Double.doubleToLongBits(s.vz));
            }
        }
    }

    /** A full stick in any of the 8 key directions moves as the keys do (vanilla's square stretch makes a diagonal 1). */
    @Test
    public void fullStickMatchesKeys() {
        PlayerSim k = start(), a = start();
        for (int t = 0; t < 120; t++) {
            float yaw = (float) (-90 + (t % 11) * 3.7);
            k.tick(yaw, keyIn(t), keyStrafe(t), t % 5 != 0, t % 9 == 0);
            float f = keyIn(t), st = keyStrafe(t);
            if (f != 0 && st != 0) { // what KeyboardInput hands over: normalised
                f *= (float) Math.sqrt(0.5);
                st *= (float) Math.sqrt(0.5);
            }
            a.tickAnalog(yaw, f, st, t % 5 != 0, t % 9 == 0);
            assertEquals(k.x, a.x, 1e-5);
            assertEquals(k.y, a.y, 1e-5);
            assertEquals(k.z, a.z, 1e-5);
        }
    }

    /** Ground walk with a half stick: per-tick step a / (1 - 0.546) with a = 0.98 * 0.5 * 0.1. */
    @Test
    public void halfStickWalkSpeed() {
        PlayerSim s = start();
        for (int i = 0; i < 60; i++) s.tickAnalog(-90, 0.5f, 0, false, false);
        double before = s.x;
        s.tickAnalog(-90, 0.5f, 0, false, false);
        double a = 0.98 * 0.5 * 0.1, drag = 0.6 * 0.91;
        assertEquals(a / (1 - drag), s.x - before, 2e-4);
    }

    /** Vanilla 26.3 has no 0.8 forward threshold: any forward impulse keeps a sprint, and sprint speed scales. */
    @Test
    public void sprintWithSmallForward() {
        PlayerSim s = start();
        for (int i = 0; i < 60; i++) s.tickAnalog(-90, 0.2f, 0, true, false);
        assertTrue(s.sprinting);
        double before = s.x;
        s.tickAnalog(-90, 0.2f, 0, true, false);
        double a = 0.98 * 0.2 * 0.13, drag = 0.6 * 0.91;
        assertEquals(a / (1 - drag), s.x - before, 2e-4);
        s.tickAnalog(-90, 0, 0.5f, true, false); // strafe only: no forward impulse ends the sprint
        assertTrue(!s.sprinting);
    }

    /** A stick at 30 degrees off forward pushes along that angle, which no key combination can. */
    @Test
    public void stickAngle() {
        PlayerSim s = start();
        float f = (float) Math.cos(Math.toRadians(30)), st = (float) Math.sin(Math.toRadians(30));
        s.tickAnalog(0, f, st, false, false); // yaw 0 faces +z; +strafe (A) is +x
        double ang = Math.toDegrees(Math.atan2(s.x - 0.5, s.z - 0.5));
        assertEquals(30, ang, 0.01);
    }

    @Test
    public void stickArriveBrakesNearAndPushesFar() {
        assertTrue(KinematicController.stickArrive(1.0, 0.0) > 0.99);
        assertTrue(KinematicController.stickArrive(0.0, 0.2) < -0.99);
        float mid = KinematicController.stickArrive(0.5, 0.045);
        assertTrue(mid > -1 && mid < 1);
    }
}
