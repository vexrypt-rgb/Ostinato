package baritone.behavior;

import baritone.health.DamageMath;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: the spar's damage against vanilla 26.3 values (Player.attack, CombatRules, LivingEntity.hurtServer). */
public class SparRulesTest {
    private static final float E = 1e-3f;

    @Test
    public void attackDamage() {
        SparKit.Weapon ds = SparKit.diamondSword(0), ns5 = SparKit.netheriteSword(5);
        assertEquals(7f, SparRules.attackDamage(ds, -1, 1f, false, 0), E);
        assertEquals(10.5f, SparRules.attackDamage(ds, -1, 1f, true, 0), E); // crit x1.5
        assertEquals(2.8f, SparRules.attackDamage(ds, -1, 0.5f, false, 0), E); // 7 * (0.2 + 0.25 * 0.8)
        assertEquals(7f, SparRules.attackDamage(ds, -1, 0.85f, true, 0) / (0.2f + 0.85f * 0.85f * 0.8f), E); // under 0.9: no crit
        assertEquals(11f, SparRules.attackDamage(ns5, -1, 1f, false, 0), E); // 8 + Sharpness V 3
        assertEquals(15f, SparRules.attackDamage(ns5, -1, 1f, true, 0), E); // 8 * 1.5 + 3
        assertEquals(10f, SparRules.attackDamage(ds, 0, 1f, false, 0), E); // Strength I: +3
        // mace: 4 a block for 3, 2 a block for the next 5; the smash is added before the crit's 1.5
        SparKit.Weapon mace = SparKit.mace(0, 0), dense = SparKit.mace(4, 0);
        assertEquals(22f, SparRules.attackDamage(mace, -1, 1f, false, 5), E);
        assertEquals(33f, SparRules.attackDamage(mace, -1, 1f, true, 5), E);
        assertEquals(32f, SparRules.attackDamage(dense, -1, 1f, false, 5), E); // Density IV: +0.5 * 4 a block
        assertEquals(6f, SparRules.attackDamage(mace, -1, 1f, false, 1.4), E); // no smash under 1.5
        // a sword is charged after 12.5 ticks (attack speed 1.6), an axe after 20
        assertEquals(1f, DamageMath.strengthScale(12, 1.6), E);
        assertTrue(DamageMath.strengthScale(18, 1.0) < 0.95f);
    }

    @Test
    public void armourAndProtection() {
        SparRules.Fighter bare = new SparRules.Fighter("v", SparKit.of("uhc"));
        // uhc: 19 armour, 6 toughness, no Protection: 7 -> 7 * (1 - (19 - 7/3.5)/25) = 2.24
        SparRules.Hit h = SparRules.melee(SparKit.diamondSword(0), 1f, false, false, false, 0, bare, false);
        assertEquals(2.24f, h.dealt, E);
        // full diamond (20, 8) with Protection IV on each piece (16): 10.5 -> 10.5 * (1 - 17.375/25) * (1 - 16/25)
        SparRules.Fighter dia = new SparRules.Fighter("v", SparKit.of("pot"));
        h = SparRules.melee(SparKit.diamondSword(0), 1f, true, false, false, 0, dia, false);
        assertTrue(h.crit);
        assertEquals(10.5f * (1 - 17.375f / 25) * 0.36f, h.dealt, E);
        // netherite (20, 12): 11 -> 11 * (1 - 17.8/25) * 0.36
        SparRules.Fighter neth = new SparRules.Fighter("v", SparKit.of("netherite-pot"));
        h = SparRules.melee(SparKit.netheriteSword(5), 1f, false, false, false, 0, neth, false);
        assertEquals(11f * (1 - 17.8f / 25) * 0.36f, h.dealt, E);
        // the armour floor: a huge hit still meets 20% of the armour (here 4 of 20)
        assertEquals(100f * (1 - 4f / 25), DamageMath.afterArmor(100, 20, 8, 0), E);
        // Resistance I: 20% off
        assertEquals(8f, DamageMath.afterMagic(10, 0, 0), E);
    }

    @Test
    public void hurtCooldown() {
        SparRules.Fighter v = new SparRules.Fighter("v", SparKit.of("uhc"));
        SparRules.Hit a = new SparRules.Hit(), b = new SparRules.Hit(), c = new SparRules.Hit();
        SparRules.hurt(v, 7, 0, a);
        for (int i = 0; i < 5; i++) v.tick(); // inside the first half of the 20-tick cooldown
        SparRules.hurt(v, 5, 0, b);
        assertTrue(b.ignored);
        SparRules.hurt(v, 9, 0, c); // a bigger hit lands only by what it exceeds the last: 2 before armour
        assertFalse(c.ignored);
        assertEquals(DamageMath.afterArmor(2, 19, 6, 0), c.dealt, E);
        for (int i = 0; i < 10; i++) v.tick();
        SparRules.Hit d = new SparRules.Hit();
        SparRules.hurt(v, 5, 0, d); // past the half: a full hit again
        assertEquals(DamageMath.afterArmor(5, 19, 6, 0), d.dealt, E);
    }

    @Test
    public void shieldsAndAxes() {
        SparRules.Fighter v = new SparRules.Fighter("v", SparKit.of("axe+shield"));
        v.shieldUp = 4;
        assertFalse(v.blocking()); // still in its warm-up
        v.shieldUp = 5;
        assertTrue(v.blocking());
        SparRules.Hit s = SparRules.melee(SparKit.diamondSword(0), 1f, false, false, true, 0, v, true);
        assertTrue(s.blocked);
        assertEquals(20f, v.hp, E);
        SparRules.Hit back = SparRules.melee(SparKit.diamondSword(0), 1f, false, false, true, 0, v, false);
        assertFalse(back.blocked); // a shield covers only the front
        v.invul = 0;
        SparRules.Hit ax = SparRules.melee(SparKit.diamondAxe(), 1f, false, false, true, 0, v, true);
        assertTrue(ax.blocked && ax.shieldDisabled);
        assertEquals(SparRules.SHIELD_DISABLE, v.shieldDisabled);
        assertFalse(v.blocking());
        for (int i = 0; i < SparRules.SHIELD_DISABLE; i++) v.tick();
        v.shieldUp = 5;
        assertTrue(v.blocking());
    }

    @Test
    public void applesTotemsPotionsRegen() {
        SparRules.Fighter v = new SparRules.Fighter("v", SparKit.of("smp"));
        v.hp = 10;
        assertTrue(v.startBite(false));
        for (int i = 0; i < SparRules.EAT_TICKS; i++) v.tick();
        assertEquals(4f, v.absorption, E); // Absorption I
        assertEquals(15, v.gapples);
        float before = v.hp;
        for (int i = 0; i < 100; i++) v.tick(); // Regeneration II heals 1 every 25 ticks for 5 s
        assertTrue(v.hp >= before + 4 - E && v.hp <= before + 6 + E); // 4 from regen, up to 1 from food
        // a hit eats absorption first
        SparRules.Hit h = new SparRules.Hit();
        v.invul = 0;
        float hp = v.hp;
        v.absorption = 4;
        SparRules.hurt(v, 6, 0, h);
        assertEquals(Math.max(0, 4 - h.dealt), v.absorption, E);
        assertEquals(hp - Math.max(0, h.dealt - 4), v.hp, E);
        // a totem saves a lethal hit: 1 health, Absorption II (8), Regeneration II
        v.hp = 1;
        v.absorption = 0;
        v.invul = 0;
        SparRules.Hit kill = new SparRules.Hit();
        SparRules.hurt(v, 100, 0, kill);
        assertTrue(kill.totem && !kill.killed);
        assertEquals(1f, v.hp, E);
        assertEquals(8f, v.absorption, E);
        assertEquals(2, v.totems);
        assertEquals(1, v.regenAmp);
        // no totems: dead
        SparRules.Fighter p = new SparRules.Fighter("p", SparKit.of("pot"));
        p.hp = 1;
        SparRules.Hit k2 = new SparRules.Hit();
        SparRules.hurt(p, 100, 0, k2);
        assertTrue(k2.killed && p.dead());
        // Healing II splash at our feet: 8
        SparRules.Fighter q = new SparRules.Fighter("q", SparKit.of("pot"));
        q.hp = 5;
        assertTrue(q.splashHeal());
        assertEquals(13f, q.hp, E);
        assertEquals(5, q.heals);
        // an enchanted apple: Absorption IV (16)
        SparRules.Fighter e = new SparRules.Fighter("e", SparKit.of("sword"));
        e.egapples = 1;
        assertTrue(e.startBite(true));
        for (int i = 0; i < SparRules.EAT_TICKS; i++) e.tick();
        assertEquals(16f, e.absorption, E);
        assertEquals(0, e.resistAmp);
    }

    @Test
    public void knockback() {
        double[] v = SparRules.knockback(0, 0, 0, true, 0.4, 0, 1, 0);
        assertEquals(0.4, v[0], 1e-9);
        assertEquals(0.4, v[1], 1e-9);
        double[] n = SparRules.knockback(0, 0, 0, true, 0.4, 0.4, 1, 0); // full netherite: 40% less
        assertEquals(0.24, n[0], 1e-9);
        double[] air = SparRules.knockback(0.2, -0.3, 0, false, 0.4, 0, 0, 1);
        assertEquals(0.1, air[0], 1e-9); // speed halved
        assertEquals(-0.3, air[1], 1e-9); // in the air: no lift
        assertEquals(0.4, air[2], 1e-9);
        // a sprint hit is charged and adds the second push
        SparRules.Fighter f = new SparRules.Fighter("f", SparKit.of("sword"));
        SparRules.Hit h = SparRules.melee(SparKit.diamondSword(0), 1f, false, true, true, 0, f, false);
        assertTrue(h.sprintKnock && !h.sweep);
        SparRules.Fighter g = new SparRules.Fighter("g", SparKit.of("sword"));
        SparRules.Hit sw = SparRules.melee(SparKit.diamondSword(0), 1f, false, false, true, 0, g, false);
        assertTrue(sw.sweep && !sw.sprintKnock);
    }

    @Test
    public void everyKitExists() {
        for (String n : SparKit.NAMES) assertNotNull(n, SparKit.of(n));
        assertNull(SparKit.of("nope"));
    }
}
