/*
 * Ostinato: new file (freecam ghost sparring). Not part of upstream Baritone.
 */
package baritone.behavior;

import baritone.health.DamageMath;

/**
 * Ostinato: vanilla 26.3's melee rules for one spar side, on its own numbers (no world, nothing sent): attack strength,
 * crits, the mace's smash, sweeping, armour and toughness, Protection, Resistance, shields and the axe that disables
 * them, the hurt cooldown, absorption, golden apples, totems, Regeneration, healing potions and knockback. The damage
 * arithmetic is {@link DamageMath}'s, which names the vanilla method each formula copies.
 */
public final class SparRules {
    private SparRules() {}

    /** Player.attack: ticks of an axe-disabled shield (the axe's disable_blocking_for_seconds, 5 s). */
    public static final int SHIELD_DISABLE = 100;
    /** A shield blocks once it has been up this many ticks (the shield's block delay, 0.25 s). */
    public static final int SHIELD_DELAY = 5;
    public static final int EAT_TICKS = 32;

    public static final class Fighter {
        public final String name;
        public final SparKit kit;
        public float hp = 20, absorption;
        public int regenAmp = -1, regenTicks, regenClock, resistAmp = -1, resistTicks, absorbTicks;
        public int invul;
        public float lastHurt;
        public int gapples, egapples, totems, heals, crystals;
        public int eating; // ticks left of a bite, 0 when not eating
        public boolean eatingEnchanted;
        public int shieldUp; // ticks the shield has been raised, 0 when down
        public int shieldDisabled; // ticks the shield stays disabled
        public int naturalClock;
        public int pops, deaths;

        public Fighter(String name, SparKit kit) {
            this.name = name;
            this.kit = kit;
            reset();
        }

        public void reset() {
            hp = 20;
            absorption = 0;
            regenAmp = resistAmp = -1;
            regenTicks = resistTicks = absorbTicks = invul = eating = shieldUp = shieldDisabled = 0;
            lastHurt = 0;
            gapples = kit.gapples;
            egapples = kit.egapples;
            totems = kit.totems;
            heals = kit.heals;
            crystals = kit.crystals;
        }

        public boolean blocking() {
            return kit.shield && shieldUp >= SHIELD_DELAY && shieldDisabled == 0;
        }

        public boolean dead() {
            return hp <= 0;
        }

        /** One game tick: hurt cooldown, effects, Regeneration (DamageMath.regenInterval), food regeneration, bites. */
        public void tick() {
            if (invul > 0) invul--;
            if (shieldDisabled > 0) {
                shieldDisabled--;
                shieldUp = 0;
            }
            if (resistTicks > 0 && --resistTicks == 0) resistAmp = -1;
            if (absorbTicks > 0 && --absorbTicks == 0) absorption = 0;
            if (regenTicks > 0) {
                if (++regenClock % DamageMath.regenInterval(regenAmp) == 0) heal(1);
                if (--regenTicks == 0) regenAmp = -1;
            }
            // FoodData with a full bar: a heal every 80 ticks once saturation is gone (both sides alike)
            if (++naturalClock % 80 == 0) heal(1);
            if (eating > 0 && --eating == 0) finishBite();
        }

        public void heal(float n) {
            if (hp > 0) hp = Math.min(20, hp + n);
        }

        /** Start a bite of a golden apple (enchanted if {@code enchanted}); false with none left. */
        public boolean startBite(boolean enchanted) {
            if (eating > 0 || (enchanted ? egapples : gapples) <= 0) return false;
            eating = EAT_TICKS;
            eatingEnchanted = enchanted;
            shieldUp = 0;
            return true;
        }

        private void finishBite() {
            if (eatingEnchanted) {
                egapples--;
                effectAbsorption(3, 2400); // Absorption IV
                effectRegen(1, 400); // Regeneration II, 20 s
                resistAmp = 0;
                resistTicks = 6000;
            } else {
                gapples--;
                effectAbsorption(0, 2400); // Absorption I, 2 min
                effectRegen(1, 100); // Regeneration II, 5 s
            }
        }

        /** AbsorptionMobEffect: the amount is raised to the level's, never lowered. */
        public void effectAbsorption(int amp, int ticks) {
            absorption = Math.max(absorption, DamageMath.absorption(amp));
            absorbTicks = Math.max(absorbTicks, ticks);
        }

        /** MobEffectInstance.update: a stronger level replaces, the same level keeps the longer time. */
        public void effectRegen(int amp, int ticks) {
            if (amp > regenAmp || amp == regenAmp && ticks > regenTicks) {
                if (amp > regenAmp) regenClock = 0;
                regenAmp = amp;
                regenTicks = ticks;
            }
        }

        /** A splash of Healing II at our own feet: 8 (DamageMath.instantHeal). */
        public boolean splashHeal() {
            if (heals <= 0) return false;
            heals--;
            heal(DamageMath.instantHeal(1, 1.0));
            return true;
        }
    }

    /** What one hit did. */
    public static final class Hit {
        public float raw, dealt;
        public boolean crit, sweep, blocked, shieldDisabled, ignored, totem, killed;
        /** Knockback strength of the hurt (before knockback resistance), and whether a sprint's second push of 0.5 follows. */
        public double knockback;
        public boolean sprintKnock;
    }

    /** Player.attack's damage before armour: the attribute (with Strength), the charge, crit, sharpness and the mace. */
    public static float attackDamage(SparKit.Weapon w, int strengthAmp, float scale, boolean critEligible, double fall) {
        float attr = w.damage + (strengthAmp >= 0 ? 3 * (strengthAmp + 1) : 0);
        float smash = w.mace ? DamageMath.maceSmash(fall, w.density) : 0;
        return DamageMath.melee(attr, DamageMath.sharpness(w.sharpness), smash, scale, critEligible);
    }

    /**
     * One melee hit. {@code critEligible}: falling, not on the ground, not sprinting, not in water (Player.attack's
     * condition); the 0.9 charge is checked here. {@code facing}: the victim faces the attacker (a shield covers the
     * front half). Returns what happened; the victim's state is changed.
     */
    public static Hit melee(SparKit.Weapon w, float scale, boolean critEligible, boolean sprinting, boolean onGround, double fall,
                            Fighter vic, boolean facing) {
        Hit h = new Hit();
        h.crit = critEligible && scale > 0.9f;
        h.raw = attackDamage(w, -1, scale, critEligible, fall);
        boolean sprintKnock = sprinting && scale > 0.9f;
        // Player.attack: a charged sword swing on the ground that is neither a crit nor a sprint knock sweeps
        h.sweep = w.sword && scale > 0.9f && !h.crit && !sprintKnock && onGround;
        if (vic.blocking() && facing) {
            h.blocked = true;
            if (w.axe) { // the axe disables the shield it hits
                vic.shieldDisabled = SHIELD_DISABLE;
                vic.shieldUp = 0;
                h.shieldDisabled = true;
            }
            return h;
        }
        h.knockback = 0.4; // LivingEntity.hurt's push; a sprint adds Player.attack's own second push of 0.5 (see sprintKnock)
        h.sprintKnock = sprintKnock;
        hurt(vic, h.raw, w.breach, h);
        return h;
    }

    /** Explosion damage (a crystal, power 6) at {@code dist}, normal difficulty, fully exposed, then the same hurt path. */
    public static Hit blast(Fighter vic, double dist) {
        Hit h = new Hit();
        h.raw = DamageMath.difficulty(DamageMath.explosion(dist, 6, 1), 2);
        h.knockback = 0;
        hurt(vic, h.raw, 0, h);
        return h;
    }

    /**
     * LivingEntity.hurtServer and actuallyHurt: inside the second half of the hurt cooldown only the part above the
     * last hurt lands; then armour (toughness, breach), Resistance and Protection, absorption, health; a totem saves.
     */
    public static void hurt(Fighter vic, float amount, int breach, Hit h) {
        if (vic.dead()) {
            h.ignored = true;
            return;
        }
        float dmg;
        if (vic.invul > 10) {
            if (amount <= vic.lastHurt) {
                h.ignored = true;
                return;
            }
            dmg = amount - vic.lastHurt;
            vic.lastHurt = amount;
        } else {
            vic.lastHurt = amount;
            vic.invul = 20;
            dmg = amount;
        }
        dmg = DamageMath.afterArmor(dmg, vic.kit.armor, vic.kit.toughness, breach);
        dmg = DamageMath.afterMagic(dmg, vic.resistAmp, vic.kit.protection);
        float fromAbs = Math.min(vic.absorption, dmg);
        vic.absorption -= fromAbs;
        float left = dmg - fromAbs;
        vic.hp -= left;
        h.dealt = dmg;
        if (vic.hp <= 0) {
            if (vic.totems > 0) { // LivingEntity.checkTotemDeathProtection
                vic.totems--;
                vic.pops++;
                vic.hp = 1;
                vic.absorption = 0;
                vic.regenAmp = vic.resistAmp = -1;
                vic.regenTicks = vic.resistTicks = 0;
                vic.effectRegen(1, 900); // Regeneration II, 45 s
                vic.effectAbsorption(1, 100); // Absorption II, 5 s
                h.totem = true;
            } else {
                vic.hp = 0;
                vic.deaths++;
                h.killed = true;
            }
        }
    }

    /** LivingEntity.knockback: halve the speed, push {@code strength} (less knockback resistance) along (dirX, dirZ), up 0.4 at most on the ground. */
    public static double[] knockback(double vx, double vy, double vz, boolean onGround, double strength, double resist, double dirX, double dirZ) {
        strength *= 1 - resist;
        if (strength <= 0) return new double[]{vx, vy, vz};
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1e-6) return new double[]{vx, vy, vz};
        double kx = dirX / len * strength, kz = dirZ / len * strength;
        return new double[]{vx / 2 + kx, onGround ? Math.min(0.4, vy / 2 + strength) : vy, vz / 2 + kz};
    }
}
