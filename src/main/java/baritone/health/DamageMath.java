/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.health;

/**
 * Ostinato: vanilla 26.3's damage and healing arithmetic, without Minecraft types, so it can be unit tested and run
 * on what the client sees of someone else. Every formula names the vanilla method it copies.
 */
public final class DamageMath {
    private DamageMath() {}

    /** Player.getAttackStrengthScale(0.5): ticks since the attacker's last swing over its attack delay (20 / attack speed). */
    public static float strengthScale(int ticksSinceSwing, double attackSpeed) {
        float delay = (float) (1.0 / Math.max(1e-3, attackSpeed) * 20.0);
        return Math.max(0, Math.min(1, (ticksSinceSwing + 0.5f) / delay));
    }

    /**
     * Player.attack: the damage of one melee hit before the victim's armour. {@code attackDamage} is the attribute
     * (1 plus the weapon's modifier plus Strength's 3 a level less Weakness's 4), {@code enchantBonus} what Sharpness or
     * Smite/Bane add against this victim, {@code itemBonus} the mace's smash bonus.
     */
    public static float melee(float attackDamage, float enchantBonus, float itemBonus, float scale, boolean crit) {
        float magic = scale * enchantBonus;
        float base = attackDamage * (0.2f + scale * scale * 0.8f);
        base += itemBonus;
        if (crit && scale > 0.9f) base *= 1.5f;
        return base + magic;
    }

    /** Sharpness (1.21+ data): 1 at level 1, half a point per level after. */
    public static float sharpness(int level) {
        return level <= 0 ? 0 : 0.5f * level + 0.5f;
    }

    /** Smite against undead, Bane of Arthropods against arthropods, Impaling against the impaling-sensitive: 2.5 a level. */
    public static float smiteLike(int level) {
        return level <= 0 ? 0 : 2.5f * level;
    }

    /** MaceItem.getAttackDamageBonus with Density's per-block bonus, for a fall of {@code fall} blocks. */
    public static float maceSmash(double fall, int density) {
        if (fall <= 1.5) return 0;
        double bonus = fall <= 3 ? 4 * fall : fall <= 8 ? 12 + 2 * (fall - 3) : 22 + (fall - 8);
        return (float) (bonus + 0.5 * density * fall);
    }

    /** CombatRules.getDamageAfterAbsorb; {@code breach} levels lower armour effectiveness 15% each. */
    public static float afterArmor(float damage, float armor, float toughness, int breach) {
        float t = 2.0f + toughness / 4.0f;
        float real = clamp(armor - damage / t, armor * 0.2f, 20.0f);
        float fraction = clamp(real / 25.0f * (1 - 0.15f * breach), 0, 1);
        return damage * (1.0f - fraction);
    }

    /** LivingEntity.getDamageAfterMagicAbsorb: Resistance (amplifier, -1 for none), then enchantment protection points. */
    public static float afterMagic(float damage, int resistanceAmp, float protection) {
        if (resistanceAmp >= 0) {
            int absorb = 25 - (resistanceAmp + 1) * 5;
            damage = Math.max(damage * absorb / 25.0f, 0.0f);
        }
        if (damage <= 0) return 0;
        if (protection > 0) damage = damage * (1.0f - clamp(protection, 0, 20) / 25.0f);
        return damage;
    }

    /** Protection points of one armour set against one kind of damage: Protection 1 a level, the specific kinds 2 (fall 3). */
    public static float protection(int protectionLevels, int specificLevels, int specificWeight) {
        return protectionLevels + specificLevels * specificWeight;
    }

    /** LivingEntity.calculateFallDamage: floor((fall - safe distance - jump boost) * multiplier), none under it. */
    public static int fall(double fallDistance, double safeFall, double jumpBoostLevels, double multiplier) {
        double power = fallDistance - safeFall - jumpBoostLevels;
        return power <= 0 ? 0 : (int) Math.floor(power * multiplier);
    }

    /** HealOrHarmMobEffect for a splash at {@code scale} (1 - distance/4): what Instant Health heals. */
    public static int instantHeal(int amplifier, double scale) {
        return (int) (scale * (4 << amplifier) + 0.5);
    }

    /** ... and what Instant Damage hurts (before Resistance and Protection: it is magic, armour does not count). */
    public static int instantHarm(int amplifier, double scale) {
        return (int) (scale * (6 << amplifier) + 0.5);
    }

    /** ThrownPotion: the scale for a box-to-box squared distance under 16, 0 past it. */
    public static double splashScale(double distSq) {
        return distSq < 16 ? 1.0 - Math.sqrt(distSq) / 4.0 : 0;
    }

    /** Player.hurtServer's difficulty scaling for damage that scales with it (mob attacks, explosions): 0 peaceful .. 3 hard. */
    public static float difficulty(float damage, int difficulty) {
        switch (difficulty) {
            case 0: return 0;
            case 1: return Math.min(damage / 2.0f + 1.0f, damage);
            case 3: return damage * 3.0f / 2.0f;
            default: return damage;
        }
    }

    /** ServerExplosion: damage at {@code dist} from an explosion of {@code power} (crystal 6), fully exposed. */
    public static float explosion(double dist, double power, double exposure) {
        double r = power * 2;
        if (dist > r) return 0;
        double impact = (1 - dist / r) * exposure;
        return (float) ((impact * impact + impact) / 2.0 * 7.0 * r + 1.0);
    }

    /** Ticks between one heal of Regeneration, one hurt of Poison or of Wither. */
    public static int regenInterval(int amp) {
        return Math.max(1, 50 >> amp);
    }

    public static int poisonInterval(int amp) {
        return Math.max(1, 25 >> amp);
    }

    public static int witherInterval(int amp) {
        return Math.max(1, 40 >> amp);
    }

    /** AbsorptionMobEffect: four points a level. */
    public static float absorption(int amp) {
        return 4.0f * (amp + 1);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
