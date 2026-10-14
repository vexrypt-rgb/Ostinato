package baritone.pathing.kinematic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: MovementTech against the vanilla 26.3 formulas it copies (each value re-derived here independently). */
public class MovementTechTest {

    @Test
    public void featherFallingAndProtection() {
        assertEquals(12, MovementTech.fallEpf(4));                 // Feather Falling IV: 3 a level
        assertEquals(28, MovementTech.fallEpf(4, 4, 4, 4, 4));     // + Protection IV on all four
        assertEquals(1 - 12 / 25.0, MovementTech.epfFactor(12), 1e-12); // 52% off
        assertEquals(0.2, MovementTech.epfFactor(28), 1e-12);      // capped at 20: 80% off
        // 10 blocks, Feather IV + Prot IV x4 + Resistance III (0.4): floor(10 - 3) = 7 * 0.4 * 0.2
        assertEquals(7 * 0.4 * 0.2, MovementTech.fallDamage(10, 3, 0.4, 28), 1e-9);
        assertEquals(0, MovementTech.fallDamage(3, 3, 1, 0), 0);
        assertEquals(1, MovementTech.fallDamage(4, 3, 1, 0), 1e-12);
    }

    @Test
    public void riptide() {
        assertEquals(1.5f, MovementTech.riptideStrength(1), 0);
        assertEquals(2.25f, MovementTech.riptideStrength(2), 0);
        assertEquals(3.0f, MovementTech.riptideStrength(3), 0);
        double[] p = MovementTech.riptidePush(-90, -30, 3);
        assertEquals(3.0, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]), 1e-5);
        assertEquals(3.0 * Math.cos(Math.toRadians(30)), p[0], 1e-5);   // yaw -90 is +x
        assertEquals(3.0 * Math.sin(Math.toRadians(30)), p[1], 1e-5);   // pitch -30 is up
        // a flat-ground launch around 35 degrees up carries about 15 / 24 / 33 blocks
        assertEquals(15.2, MovementTech.riptideFlatReach(1, -35), 0.5);
        assertEquals(33.5, MovementTech.riptideFlatReach(3, -35), 0.5);
    }

    @Test
    public void windChargeSelfLaunch() {
        // burst 0.25 over the floor, feet 0.25 away over 2.4: (1 - 0.25/2.4) * 1.22, straight up toward the eyes
        double[] w = MovementTech.windSelfLaunch(1.62);
        assertEquals((1 - 0.25 / 2.4) * 1.22F, w[1], 1e-5);
        assertEquals(0, w[0], 1e-12);
        assertTrue(MovementTech.windSelfLaunchApex(1.62) > 6.8 && MovementTech.windSelfLaunchApex(1.62) < 7.1);
        // nothing past twice the radius
        assertEquals(0, MovementTech.windImpulse(0, 0, 0, 1.62, 2.5, 0, 0, 1)[0], 0);
        // fall damage only below the impact height
        assertEquals(0, MovementTech.windFallDistance(7, 64, 64), 0);
        assertEquals(5, MovementTech.windFallDistance(12, 64, 59), 0);
    }

    @Test
    public void lunge() {
        assertEquals(0.458, MovementTech.lungeImpulse(1, 0), 1e-6);
        assertEquals(1.374, MovementTech.lungeImpulse(3, 0), 1e-6);
        assertEquals(0.458 * 2 * Math.cos(Math.toRadians(40)), MovementTech.lungeImpulse(2, 40), 1e-6);
        assertEquals(12f, MovementTech.lungeExhaustion(3), 0);
        assertEquals(13, MovementTech.lungeCooldownTicks(0.65f));  // wooden spear
        assertEquals(23, MovementTech.lungeCooldownTicks(1.15f));  // netherite spear
        // the impulse decays with the 0.546 ground drag: about 0.458 / 0.454 a level on the flat, more if jumped into
        assertEquals(1.0, MovementTech.lungeGain(1, false), 0.05);
        assertTrue(MovementTech.lungeGain(3, true) > 6.5);
    }

    @Test
    public void elytraGroundTakeoff() {
        // without a rocket a glide off a flat jump touches back down within a few blocks
        assertTrue(MovementTech.groundTakeoff(0, 999, 1)[0] <= 0);
        // with one fired on opening, nose 30 up, it never touches down and climbs
        double[] r = MovementTech.groundTakeoff(-30, 0, 1);
        assertTrue(r[0] > 0.9 && r[1] > 50);
        assertEquals(20, MovementTech.fireworkMinLife(1));
    }

    @Test
    public void ice() {
        assertEquals(0.98f, MovementTech.ICE_FRICTION, 0);
        assertEquals(0.989f, MovementTech.BLUE_ICE_FRICTION, 0);
        // plain sprint 0.2806; on ice the slower push and the longer glide cancel out to about the same
        assertEquals(0.2806, MovementTech.steadySpeed(0.6f, false, false), 1e-3);
        assertEquals(0.270, MovementTech.steadySpeed(0.98f, false, false), 2e-3);
        // sprint-jumping on ice ~0.46, under a 2-up ceiling with jump tapped ~0.97 (ice), ~0.99 (blue ice)
        assertEquals(0.46, MovementTech.steadySpeed(0.98f, true, false), 0.01);
        assertEquals(0.97, MovementTech.steadySpeed(0.98f, true, true), 0.01);
        assertEquals(0.995, MovementTech.steadySpeed(0.989f, true, true), 0.01);
        assertEquals(0.3 / (1 - 0.98f * 0.91f), MovementTech.slideDistance(0.3, 0.98f), 1e-9);
    }
}
