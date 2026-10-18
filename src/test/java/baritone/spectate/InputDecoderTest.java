/*
 * Ostinato: new file (spectator learning tests). Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.pathing.kinematic.PlayerSim;
import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: decodes scripted movement the way a spectator sees it: positions every second tick at the 1/4096 grid of
 * the move packet, yaw rounded to the packet's byte, and checks the keys it reads against the keys that were held.
 */
public class InputDecoderTest {
    /** Flat stone floor at y=0 everywhere, with a wall at x=12 to collide with. */
    static PlayerSim.World flat() {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++) {
                    for (int by = PlayerSim.floor(minY) - 1; by <= PlayerSim.floor(maxY); by++) {
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++) {
                            if (by == -1 || bx == 12 && by >= 0 && by <= 2) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
                        }
                    }
                }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    static double q(double v) {
        return Math.round(v * 4096) / 4096.0;
    }

    static float qYaw(float yaw) {
        return (float) Math.floor(yaw * 256f / 360f) * 360f / 256f;
    }

    static final class Score {
        int n, fwdOk, strOk, jumpOk, jumpN, sprintOk, allOk, resets;
        double confSum;

        double fwd() {
            return (double) fwdOk / n;
        }

        double str() {
            return (double) strOk / n;
        }

        double all() {
            return (double) allOk / n;
        }

        @Override
        public String toString() {
            return String.format("n=%d forward %.3f strafe %.3f keys %.3f jump %.3f (n=%d) sprint %.3f conf %.2f resets %d", n, fwd(), str(), all(),
                    jumpN == 0 ? 1.0 : (double) jumpOk / jumpN, jumpN, (double) sprintOk / n, confSum / Math.max(1, n), resets);
        }
    }

    /**
     * Runs a random keyboard player for {@code ticks} ticks, observed every {@code every} ticks, and scores the decoder
     * on the intervals whose keys did not change inside them. {@code analog}: inputs ramp like VexBot's instead of
     * snapping.
     */
    static Score run(long seed, int ticks, boolean analog, boolean knockback) {
        Random r = new Random(seed);
        PlayerSim.World w = flat();
        PlayerSim truth = new PlayerSim(w);
        truth.x = 0.5;
        truth.z = 0.5;
        truth.onGround = true;
        InputDecoder dec = new InputDecoder();
        Score sc = new Score();
        int fwd = 0, str = 0, hold = 0;
        boolean sprint = false;
        double af = 0, as = 0;
        float yaw = 0;
        int lastF = 0, lastS = 0;
        boolean same = true, jumped = false, sprintAll = true;
        double[] kb = null;
        for (int t = 1; t <= ticks; t++) {
            if (--hold <= 0) {
                fwd = r.nextInt(3) - 1;
                str = r.nextInt(3) - 1;
                sprint = fwd > 0 && r.nextBoolean();
                hold = 2 + r.nextInt(10);
            }
            if (truth.x > 10) fwd = str = 0; // stay off the wall mostly
            yaw += (float) (r.nextGaussian() * 6);
            boolean jump = truth.onGround && r.nextInt(12) == 0;
            if (analog) {
                af += Math.max(-0.35, Math.min(0.35, fwd - af));
                as += Math.max(-0.35, Math.min(0.35, str - as));
            } else {
                af = fwd;
                as = str;
            }
            if (fwd != lastF || str != lastS) same = false;
            lastF = fwd;
            lastS = str;
            jumped |= jump;
            boolean sp = sprint && af > 1e-5;
            sprintAll &= sp == (sprint && af > 1e-5);
            if (knockback && r.nextInt(40) == 0) {
                double a = r.nextDouble() * Math.PI * 2;
                truth.vx = 0.4 * Math.cos(a);
                truth.vy = 0.36;
                truth.vz = 0.4 * Math.sin(a);
                kb = new double[]{truth.vx, truth.vy, truth.vz};
            }
            truth.tickImpulse(yaw, 0.98 * as, 0.98 * af, sp, sp, jump);
            if (t % 2 == 0) {
                InputDecoder.Obs o = new InputDecoder.Obs();
                o.x = q(truth.x);
                o.y = q(truth.y);
                o.z = q(truth.z);
                o.yaw = qYaw(yaw);
                o.onGround = truth.onGround;
                o.ticks = 2;
                o.sprintFlag = truth.sprinting;
                o.kb = kb;
                kb = null;
                // knockback here is applied before the tick's own move, as the decoder's hypothesis 0
                InputDecoder.Decoded d = dec.step(w, o);
                if (d.reset) sc.resets++;
                else if (same && (!analog || Math.abs(af - fwd) < 1e-9 && Math.abs(as - str) < 1e-9)) {
                    sc.n++;
                    if (d.forward == fwd) sc.fwdOk++;
                    if (d.strafe == str) sc.strOk++;
                    if (d.forward == fwd && d.strafe == str) sc.allOk++;
                    if (d.jumpObservable) {
                        sc.jumpN++;
                        if (d.jump == jumped) sc.jumpOk++;
                    }
                    if (d.sprint == truth.sprinting) sc.sprintOk++;
                    sc.confSum += d.confidence;
                }
                same = true;
                jumped = false;
            }
        }
        return sc;
    }

    @Test
    public void keyboardWalkingDecodes() {
        Score s = run(1, 4000, false, false);
        System.out.println("keyboard: " + s);
        assertTrue(s.toString(), s.all() > 0.95);
    }

    @Test
    public void analogRampDecodes() {
        Score s = run(2, 4000, true, false);
        System.out.println("analog: " + s);
        assertTrue(s.toString(), s.all() > 0.93);
    }

    @Test
    public void knockbackIntervalsDecode() {
        Score s = run(3, 4000, false, true);
        System.out.println("knockback: " + s);
        assertTrue(s.toString(), s.all() > 0.9);
    }

    @Test
    public void standingStillIsNoKeys() {
        PlayerSim.World w = flat();
        InputDecoder dec = new InputDecoder();
        for (int i = 0; i < 10; i++) {
            InputDecoder.Obs o = new InputDecoder.Obs();
            o.x = 0.5;
            o.z = 0.5;
            o.onGround = true;
            InputDecoder.Decoded d = dec.step(w, o);
            if (i > 0) {
                assertEquals(0, d.forward);
                assertEquals(0, d.strafe);
            }
        }
    }
}
