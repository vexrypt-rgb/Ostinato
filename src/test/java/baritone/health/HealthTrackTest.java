/*
 * Ostinato: new file (health estimator tests). Not part of upstream Baritone.
 */
package baritone.health;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: the damage arithmetic against hand-worked vanilla numbers, and scripted fights against a known truth. */
public class HealthTrackTest {
    @Test
    public void netheriteCritThroughDiamondProtFour() {
        // netherite sword: 1 + 7; Sharpness V: +3; full strength crit: 8 * 1.5 + 3 = 15
        float raw = DamageMath.melee(8, DamageMath.sharpness(5), 0, 1, true);
        assertEquals(15, raw, 1e-5);
        // full diamond: 20 armour, 8 toughness: 15 * (1 - 16.25 / 25) = 5.25; Protection IV x4 = 16: * 0.36
        float a = DamageMath.afterArmor(raw, 20, 8, 0);
        assertEquals(5.25, a, 1e-4);
        assertEquals(1.89, DamageMath.afterMagic(a, -1, 16), 1e-4);
        // Resistance I takes another fifth
        assertEquals(1.512, DamageMath.afterMagic(a, 0, 16), 1e-4);
    }

    @Test
    public void attackStrength() {
        // a sword (1.6 attack speed) is full after 12.5 ticks; half-charged hits do 0.2 + 0.8 s^2 of the base
        assertEquals(1, DamageMath.strengthScale(13, 1.6), 1e-6);
        float s = DamageMath.strengthScale(6, 1.6);
        assertEquals(6.5 / 12.5, s, 1e-5);
        assertEquals(8 * (0.2 + 0.8 * s * s) + s * 3, DamageMath.melee(8, 3, 0, s, false), 1e-4);
        // no crit below 0.9
        assertEquals(DamageMath.melee(8, 0, 0, 0.85f, false), DamageMath.melee(8, 0, 0, 0.85f, true), 1e-6);
    }

    @Test
    public void potionsAndFalls() {
        assertEquals(8, DamageMath.instantHeal(1, 1));
        assertEquals(12, DamageMath.instantHarm(1, 1));
        assertEquals(4, DamageMath.instantHeal(0, 1));
        assertEquals(0.75, DamageMath.splashScale(1), 1e-9);
        assertEquals(3, DamageMath.instantHeal(0, 0.75)); // (int)(0.75*4+0.5)
        assertEquals(7, DamageMath.fall(10, 3, 0, 1));
        assertEquals(0, DamageMath.fall(3, 3, 0, 1));
    }

    @Test
    public void goldenAppleHealsFourAndGivesAbsorption() {
        HealthTrack t = new HealthTrack();
        t.kind(false, false, false);
        t.exact(10, 0, HealthTrack.Source.SYNCED);
        t.apple(false);
        assertEquals(4, t.absorption(), 1e-6);
        for (int i = 0; i < 120; i++) t.tick();
        assertEquals(14, t.health(), 1e-4); // Regeneration II for 5 s: a point every 25 ticks
    }

    @Test
    public void totemLeavesOneAndAbsorption() {
        HealthTrack t = new HealthTrack();
        t.kind(true, false, false);
        t.exact(3, 0, HealthTrack.Source.SYNCED);
        t.damage(10, 0.5);
        t.totem();
        assertEquals(1, t.health(), 1e-6);
        assertEquals(8, t.absorption(), 1e-6);
        assertEquals(1, t.confidence(), 1e-9);
    }

    @Test
    public void undeadAreHurtByRegenNot() {
        HealthTrack t = new HealthTrack();
        t.kind(false, true, true); // a zombie ignores regeneration
        t.exact(10, 0, HealthTrack.Source.SYNCED);
        t.startRegen(1, 100);
        for (int i = 0; i < 100; i++) t.tick();
        assertEquals(10, t.health(), 1e-3);
    }

    @Test
    public void absorptionSoaksFirst() {
        HealthTrack t = new HealthTrack();
        t.exact(20, 4, HealthTrack.Source.SYNCED);
        t.damage(6, 0);
        assertEquals(0, t.absorption(), 1e-6);
        assertEquals(18, t.health(), 1e-6);
    }

    /**
     * A scripted fight: a truth runs vanilla's numbers with random attack timing and crits; the track sees only the
     * events (each hit's computed damage with the attack strength guessed from swing timing) and must stay close.
     */
    @Test
    public void scriptedFightStaysClose() {
        Random r = new Random(7);
        double errSum = 0;
        int samples = 0;
        double worst = 0;
        for (int fight = 0; fight < 50; fight++) {
            HealthTrack t = new HealthTrack();
            t.kind(true, false, false);
            t.naturalRegenRate = 0; // the truth below has none
            t.exact(20, 0, HealthTrack.Source.SYNCED);
            float truth = 20, abs = 0;
            int last = 0;
            boolean ateApple = false;
            for (int tick = 1; tick < 600 && truth > 0; tick++) {
                t.tick();
                if (r.nextInt(14) == 0 && tick - last >= 8) {
                    int since = tick - last;
                    last = tick;
                    float s = DamageMath.strengthScale(since, 1.6);
                    boolean crit = s > 0.9f && r.nextInt(3) == 0;
                    float dmg = DamageMath.afterMagic(DamageMath.afterArmor(DamageMath.melee(8, 3, 0, s, crit), 20, 8, 0), -1, 16);
                    float soak = Math.min(abs, dmg);
                    abs -= soak;
                    truth -= dmg - soak;
                    // the estimator's guess at the strength is a tick off half the time
                    float sGuess = DamageMath.strengthScale(since + (r.nextBoolean() ? 1 : 0), 1.6);
                    float guess = DamageMath.afterMagic(DamageMath.afterArmor(DamageMath.melee(8, 3, 0, sGuess, crit), 20, 8, 0), -1, 16);
                    t.damage(guess, 0.3);
                }
                if (!ateApple && truth < 8) {
                    ateApple = true;
                    t.apple(false);
                    abs = Math.max(abs, 4);
                    // the truth's regen ticks: next 100 ticks a point every 25 starting now
                    regenLeft = 100;
                }
                if (regenLeft > 0) {
                    if (regenLeft % 25 == 0) truth = Math.min(20, truth + 1);
                    regenLeft--;
                }
                if (tick % 10 == 0 && truth > 0) {
                    double e = Math.abs(t.health() + t.absorption() - truth - abs);
                    errSum += e;
                    worst = Math.max(worst, e);
                    samples++;
                }
            }
            regenLeft = 0;
        }
        double mae = errSum / samples;
        System.out.printf("scripted fights: mean abs error %.3f hp, worst %.3f, %d samples%n", mae, worst, samples);
        assertTrue("mae " + mae, mae < 0.6);
    }

    private int regenLeft;
}
