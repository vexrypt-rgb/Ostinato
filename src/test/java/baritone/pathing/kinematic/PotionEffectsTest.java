package baritone.pathing.kinematic;

import baritone.utils.ExperimentalMovement;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: PlayerSim under potion effects against the vanilla 26.3 formulas (LivingEntity.travelInAir,
 * getEffectiveGravity, getJumpPower/getJumpBoostPower, MobEffects' attribute modifiers, calculateFallDamage).
 */
public class PotionEffectsTest {

    private static PlayerSim.World floor() {
        return new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int bx = PlayerSim.floor(minX); bx <= PlayerSim.floor(maxX); bx++)
                    for (int by = PlayerSim.floor(minY); by <= PlayerSim.floor(maxY); by++)
                        for (int bz = PlayerSim.floor(minZ); bz <= PlayerSim.floor(maxZ); bz++)
                            if (by == -1) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    private static PlayerSim at(double y, boolean ground) {
        PlayerSim s = new PlayerSim(floor());
        s.x = 0.5; s.y = y; s.z = 0.5; s.onGround = ground;
        return s;
    }

    /** The vanilla vertical step; -1 levitation is none. */
    private static double vanilla(double vy, double g, boolean slowFalling, int lev) {
        if (Math.abs(vy) < 0.003) vy = 0;
        double y = lev >= 0 ? vy + (0.05 * (lev + 1) - vy) * 0.2 : vy - (slowFalling && vy <= 0 ? Math.min(g, 0.01) : g);
        return y * 0.98;
    }

    private static void followsVanilla(PlayerSim s, int ticks) {
        double y = s.y, vy = s.vy;
        for (int t = 0; t < ticks; t++) {
            s.tick(0, false, false, false);
            if (Math.abs(vy) < 0.003) vy = 0;
            y += vy;
            vy = vanilla(vy, s.gravity, s.slowFalling, s.levitation);
            assertEquals("y at tick " + t, y, s.y, 1e-9);
            assertEquals("vy at tick " + t, vy, s.vy, 1e-9);
        }
    }

    @Test
    public void plainFallUnchanged() {
        PlayerSim s = at(100, false);
        followsVanilla(s, 60);
        // terminal speed of a plain fall is 3.92 b/t
        for (int i = 0; i < 400 && !s.onGround; i++) s.tick(0, false, false, false);
        assertTrue(s.onGround);
    }

    @Test
    public void slowFallingCapsGravity() {
        PlayerSim s = at(1000, false);
        s.slowFalling = true;
        followsVanilla(s, 200);
        // terminal velocity 0.01*0.98/0.02 = 0.49 b/t
        for (int i = 0; i < 300; i++) s.tick(0, false, false, false);
        assertEquals(-0.49, s.vy, 1e-3);
    }

    @Test
    public void slowFallingJumpRisesAtFullGravity() {
        // vanilla only caps gravity while vy <= 0: the rise of a jump is the plain one
        PlayerSim plain = at(0, true), slow = at(0, true);
        slow.slowFalling = true;
        plain.tick(0, false, false, true);
        slow.tick(0, false, false, true);
        for (int i = 0; i < 5; i++) {
            assertEquals(plain.y, slow.y, 1e-12);
            plain.tick(0, false, false, false);
            slow.tick(0, false, false, false);
        }
    }

    @Test
    public void levitationChasesTarget() {
        for (int amp = 0; amp < 4; amp++) {
            PlayerSim s = at(10, false);
            s.levitation = amp;
            followsVanilla(s, 120);
            // fixed point of v = (v + (t - v) * 0.2) * 0.98: v = 0.196 t / 0.216
            double target = 0.05 * (amp + 1);
            assertEquals(0.98 * 0.2 * target / (1 - 0.98 * 0.8), s.vy, 1e-4);
        }
    }

    /** getJumpPower: 0.42 * blockJumpFactor + 0.1 * (amp + 1); the apex follows the plain gravity loop. */
    @Test
    public void jumpBoostApex() {
        double[] expectedApex = new double[6];
        for (int amp = -1; amp < 5; amp++) {
            double v = 0.42 + (amp >= 0 ? 0.1 * (amp + 1) : 0), y = 0, top = 0;
            for (int t = 0; t < 40 && v > 0; t++) {
                y += v;
                top = Math.max(top, y);
                v = vanilla(v, 0.08, false, -1);
            }
            expectedApex[amp + 1] = top;
            PlayerSim s = at(0, true);
            s.jumpBoost = amp >= 0 ? (float) (0.1F * (amp + 1.0F)) : 0;
            s.tick(0, false, false, true);
            double simTop = s.y;
            for (int t = 0; t < 40 && !s.onGround; t++) {
                s.tick(0, false, false, false);
                simTop = Math.max(simTop, s.y);
            }
            assertEquals("apex for amplifier " + amp, top, simTop, 1e-3);
        }
        assertEquals(1.2522, expectedApex[0], 1e-3); // plain jump
        assertTrue(expectedApex[1] > 1.8 && expectedApex[2] > 2.5); // Jump Boost I clears 1.5 blocks, II clears 2
    }

    /** Speed: +20%/level, Slowness: -15%/level, ADD_MULTIPLIED_TOTAL so they multiply. */
    @Test
    public void speedAndSlownessScaleGround() {
        double[][] cases = {{1.2}, {1.4}, {0.85}, {0.4}, {1.4 * 0.4}}; // Speed I, II, Slowness I, IV (Turtle Master), both
        for (double[] c : cases) {
            PlayerSim s = at(0, true);
            s.speedScale = c[0];
            for (int i = 0; i < 80; i++) s.tick(-90, true, false, false);
            double before = s.x;
            s.tick(-90, true, false, false);
            // terminal walk speed: 0.1 * 0.98 * scale * 0.216/0.216 / (1 - 0.546)
            assertEquals(0.1 * 0.98 * c[0] / (1 - 0.6 * 0.91), s.x - before, 1e-4);
        }
    }

    @Test
    public void fallDamageWithSafeFall() {
        assertEquals(0, ExperimentalMovement.fallDamage(3));
        assertEquals(1, ExperimentalMovement.fallDamage(4));
        assertEquals(0, ExperimentalMovement.fallDamage(4, 4)); // Jump Boost I
        assertEquals(2, ExperimentalMovement.fallDamage(8, 6)); // Jump Boost III
    }

    @Test
    public void copyCarriesEffects() {
        PlayerSim a = at(5, false);
        a.slowFalling = true; a.levitation = 2; a.gravity = 0.05;
        PlayerSim b = new PlayerSim(floor()).copyFrom(a);
        assertTrue(b.slowFalling);
        assertEquals(2, b.levitation);
        assertEquals(0.05, b.gravity, 0);
    }
}
