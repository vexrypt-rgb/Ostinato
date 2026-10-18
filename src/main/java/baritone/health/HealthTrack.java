/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.health;

/**
 * Ostinato: the estimate of one entity's health, without Minecraft types. It holds a value, an absorption pool, a
 * variance (points squared) and the effects it believes are running, and is moved by events: an exact reading resets
 * it; a hurt, a heal, an eaten apple, a totem pop or a tick of an effect moves it and adds the event's own variance.
 * <p>
 * Effects started by an event we saw (an apple, a totem) run on their known clock, so their heals land on the right
 * ticks. Effects only known from their particle colour have no known clock and heal at their average rate.
 * Natural regeneration of players (unknown hunger) is the slow rate, one point per 80 ticks, with wide variance.
 */
public final class HealthTrack {
    public enum Source { NONE, SYNCED, SCOREBOARD, BOSS_BAR, INFERRED }

    float health = 20, max = 20, absorption;
    double var = 100; // nothing known yet: wide
    Source source = Source.NONE;
    boolean undead, ignoresPoisonRegen, player;
    int naturalRegenRate = 80; // ticks a point for players with unknown hunger; 0 off
    int lastHurtTick = -1000, tick;

    // effects on a known clock: remaining duration in ticks (vanilla counts down and acts when duration % interval == 0)
    int regenAmp = -1, regenLeft, poisonAmp = -1, poisonLeft, witherAmp = -1, witherLeft, absorbLeft;
    float absorbFromEffect;
    // effects seen only by their particles: no clock, average rate
    int seenRegenAmp = -1, seenPoisonAmp = -1, seenWitherAmp = -1;
    boolean seenResistance, fireResistant;
    int resistanceAmp = -1;
    boolean dead;

    public float health() {
        return health;
    }

    public float max() {
        return max;
    }

    public float absorption() {
        return absorption;
    }

    public Source source() {
        return source;
    }

    public double sd() {
        return Math.sqrt(var);
    }

    /** 1 for an exact reading, falling with the standard deviation: 1 point is 0.72, 3 points 0.37. */
    public double confidence() {
        return dead ? 1 : Math.exp(-sd() / 3.0);
    }

    public void kind(boolean player, boolean undead, boolean ignoresPoisonRegen) {
        this.player = player;
        this.undead = undead;
        this.ignoresPoisonRegen = ignoresPoisonRegen;
    }

    public void max(float m) {
        if (m > 0) max = m;
        if (health > max) health = max;
    }

    /** An exact reading: health from synced metadata, a scoreboard or a boss bar. */
    public void exact(float hp, float absorption, Source s) {
        health = Math.max(0, Math.min(max, hp));
        if (absorption >= 0) this.absorption = absorption;
        var = 0;
        source = s;
        dead = hp <= 0;
    }

    /** A reading of absorption alone (players sync it even where health is hidden). */
    public void exactAbsorption(float a) {
        absorption = Math.max(0, a);
    }

    /** First sight of an entity that tells nothing: assume full health, wide. */
    public void unseen(float maxHealth) {
        max(maxHealth);
        health = max;
        var = (max / 4) * (max / 4); // anything from a quarter down to full: assume full, wide
        source = Source.INFERRED;
    }

    /** Damage after armour and protection: absorption first, then health; {@code sd} its own uncertainty. */
    public void damage(float amount, double sd) {
        if (amount <= 0 && sd <= 0) return;
        lastHurtTick = tick;
        float soak = Math.min(absorption, amount);
        absorption -= soak;
        health -= amount - soak;
        var += sd * sd;
        source = Source.INFERRED;
        floor();
    }

    public void heal(float amount, double sd) {
        health = Math.min(max, health + amount);
        var += sd * sd;
        source = Source.INFERRED;
    }

    /** The estimate says dead but the entity lives: it has at least half a point, and we are less sure. */
    private void floor() {
        if (health < 0.5f && !dead) {
            var += sq(0.5f - health) + 1;
            health = 0.5f;
        }
    }

    public void died() {
        health = 0;
        absorption = 0;
        var = 0;
        dead = true;
    }

    /** Respawned or reloaded: full health, effects gone. */
    public void respawn() {
        dead = false;
        health = max;
        absorption = 0;
        var = 1;
        clearEffects();
    }

    private void clearEffects() {
        regenAmp = poisonAmp = witherAmp = -1;
        absorbLeft = 0;
        absorbFromEffect = 0;
    }

    /** LivingEntity.checkTotemDeathProtection: health set to 1, effects cleared, then the totem's effects. */
    public void totem() {
        health = 1;
        var = 0;
        clearEffects();
        absorption = 0;
        startRegen(1, 900);
        startAbsorption(1, 100);
        fireResistant = true;
        source = Source.INFERRED;
        dead = false;
    }

    /** A golden apple eaten (Regeneration II 5 s, Absorption I 2 min) or an enchanted one (II 20 s, IV 2 min, Resistance, Fire Resistance). */
    public void apple(boolean enchanted) {
        if (enchanted) {
            startRegen(1, 400);
            startAbsorption(3, 2400);
            resistanceAmp = Math.max(resistanceAmp, 0);
            fireResistant = true;
        } else {
            startRegen(1, 100);
            startAbsorption(0, 2400);
        }
        var += 0.25; // the apple may have gone in a tick either side
    }

    /** Regeneration on a known clock; a weaker one does not replace a stronger (MobEffectInstance.update). */
    public void startRegen(int amp, int duration) {
        if (ignoresPoisonRegen) return;
        if (amp > regenAmp || amp == regenAmp && duration > regenLeft) {
            regenAmp = amp;
            regenLeft = duration;
        }
    }

    public void startPoison(int amp, int duration) {
        if (ignoresPoisonRegen) return;
        if (amp > poisonAmp || amp == poisonAmp && duration > poisonLeft) {
            poisonAmp = amp;
            poisonLeft = duration;
        }
    }

    public void startWither(int amp, int duration) {
        if (amp > witherAmp || amp == witherAmp && duration > witherLeft) {
            witherAmp = amp;
            witherLeft = duration;
        }
    }

    /** AbsorptionMobEffect.onEffectStarted: the pool rises to at least 4 a level (it never lowers it). */
    public void startAbsorption(int amp, int duration) {
        float a = DamageMath.absorption(amp);
        absorption = Math.max(absorption, a);
        absorbFromEffect = a;
        absorbLeft = duration;
    }

    /** What the particle colours say is running now (amplifiers unknown from colour: 0 assumed). */
    public void seenEffects(boolean regen, boolean poison, boolean wither, boolean resistance, boolean fireRes) {
        seenRegenAmp = regen ? Math.max(0, regenAmp) : -1;
        seenPoisonAmp = poison ? Math.max(0, poisonAmp) : -1;
        seenWitherAmp = wither ? Math.max(0, witherAmp) : -1;
        seenResistance = resistance;
        fireResistant = fireRes || fireResistant && regenLeft > 0;
    }

    /** Resistance amplifier believed running, -1 for none. */
    public int resistance() {
        return resistanceAmp >= 0 ? resistanceAmp : seenResistance ? 0 : -1;
    }

    public boolean fireResistant() {
        return fireResistant;
    }

    /** One game tick of effects and natural regeneration. {@code hurtByEvents}: poison/wither hurts arrive as damage events, so only their clocks run here. */
    public void tick() {
        tick++;
        if (dead) return;
        if (regenAmp >= 0) {
            if (regenLeft % DamageMath.regenInterval(regenAmp) == 0 && !undead) heal(1, 0.05);
            if (--regenLeft <= 0) regenAmp = -1;
        } else if (seenRegenAmp >= 0 && !undead) {
            heal(1.0f / DamageMath.regenInterval(seenRegenAmp), 0.1);
        }
        if (poisonAmp >= 0 && --poisonLeft <= 0) poisonAmp = -1;
        if (witherAmp >= 0 && --witherLeft <= 0) witherAmp = -1;
        if (absorbLeft > 0 && --absorbLeft == 0) { // AbsorptionMobEffect.onEffectRemoved takes back what it gave
            absorption = Math.max(0, absorption - absorbFromEffect);
            absorbFromEffect = 0;
        }
        if (player && naturalRegenRate > 0 && health < max && tick - lastHurtTick > 10) {
            heal(1.0f / naturalRegenRate, 0.12);
        }
        var += 0.0005; // anything not modelled
    }

    private static double sq(double v) {
        return v * v;
    }
}
