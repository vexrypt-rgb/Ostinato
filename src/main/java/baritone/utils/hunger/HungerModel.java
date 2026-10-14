package baritone.utils.hunger;

import java.util.List;

/**
 * Ostinato code: a tick-exact copy of the 26.3 FoodData rules (net.minecraft.world.food.FoodData#tick), so the planner can
 * run food, saturation and health forward along a planned path.
 * <ul>
 * <li>Exhaustion caps at 40. Each tick, if it is above 4 (strictly), 4 is taken off and one saturation point goes, or one
 * food point once saturation is 0 (not on peaceful).</li>
 * <li>Saturation-fast regen: saturation > 0, hurt and food == 20: every 10 ticks heal min(sat, 6) / 6 and add that
 * much exhaustion (1 hp for 6 exhaustion).</li>
 * <li>Normal regen: food >= 18 and hurt: every 80 ticks heal 1 and add 6 exhaustion.</li>
 * <li>Starvation: food == 0: every 80 ticks 1 damage while health > 10 (easy), > 1 (normal), always (hard).</li>
 * <li>Sprinting needs food > 6 (FoodData.hasEnoughFood, LocalPlayer.isSprintingPossible).</li>
 * </ul>
 * Exhaustion sources (FoodConstants, ServerPlayer.checkMovementStatistics / jumpFromGround, Block.playerDestroy,
 * Player.attack, Player.actuallyHurt with DamageType.exhaustion).
 */
public final class HungerModel {
    public static final int MAX_FOOD = 20;
    public static final float EXHAUSTION_DROP = 4.0F;
    public static final float MAX_EXHAUSTION = 40.0F;
    public static final int HEALTH_TICK_COUNT = 80;
    public static final int HEALTH_TICK_COUNT_SATURATED = 10;
    public static final int HEAL_LEVEL = 18;
    public static final int SPRINT_LEVEL = 6;
    public static final float EXHAUSTION_HEAL = 6.0F;
    public static final float EXHAUSTION_JUMP = 0.05F;
    public static final float EXHAUSTION_SPRINT_JUMP = 0.2F;
    public static final float EXHAUSTION_MINE = 0.005F;
    /** Per attack that hits an entity (Player.attack). A swing that misses costs nothing but its time. */
    public static final float EXHAUSTION_ATTACK = 0.1F;
    /** Per metre sprinted on the ground. Walking and crouching are 0. */
    public static final float EXHAUSTION_SPRINT = 0.1F;
    /** Per metre swimming, walking under water or on water. */
    public static final float EXHAUSTION_SWIM = 0.01F;
    /** Damage types with exhaustion 0.1: mobs, players, arrows, lava, in_fire, hot_floor, cactus, explosions. Fall, on_fire, starve, drown are 0. */
    public static final float EXHAUSTION_DAMAGE = 0.1F;
    /** Consumable default: 1.6 s. */
    public static final int EAT_TICKS = 32;

    public enum Difficulty { PEACEFUL, EASY, NORMAL, HARD }

    public int food;
    public float saturation;
    public float exhaustion;
    public float health;
    public float maxHealth = 20;
    public int tickTimer;
    public Difficulty difficulty = Difficulty.NORMAL;
    public boolean naturalRegen = true;

    public HungerModel(int food, float saturation, float exhaustion, float health) {
        this.food = food;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.health = health;
    }

    public HungerModel copy() {
        HungerModel m = new HungerModel(food, saturation, exhaustion, health);
        m.maxHealth = maxHealth;
        m.tickTimer = tickTimer;
        m.difficulty = difficulty;
        m.naturalRegen = naturalRegen;
        return m;
    }

    public static float saturationByModifier(int nutrition, float modifier) {
        return nutrition * modifier * 2.0F;
    }

    public static float sprintExhaustion(double metres) {
        return (float) (EXHAUSTION_SPRINT * metres);
    }

    public static float swimExhaustion(double metres) {
        return (float) (EXHAUSTION_SWIM * metres);
    }

    public static float jumpExhaustion(boolean sprinting) {
        return sprinting ? EXHAUSTION_SPRINT_JUMP : EXHAUSTION_JUMP;
    }

    public static float mineExhaustion(int blocks) {
        return EXHAUSTION_MINE * blocks;
    }

    public static float attackExhaustion(int hits) {
        return EXHAUSTION_ATTACK * hits;
    }

    public boolean canSprint() {
        return food > SPRINT_LEVEL;
    }

    public boolean hurt() {
        return health > 0 && health < maxHealth;
    }

    public void addExhaustion(float amount) {
        exhaustion = Math.min(exhaustion + amount, MAX_EXHAUSTION);
    }

    /** FoodData.eat(FoodProperties): food clamps to 20, saturation to the new food level. */
    public void eat(int nutrition, float sat) {
        food = clamp(nutrition + food, 0, MAX_FOOD);
        saturation = Math.max(0, Math.min(sat + saturation, food));
    }

    /** Eat a food including its Regeneration/Absorption (the regen is folded in as instant healing, it is short). */
    public void eat(Food f) {
        eat(f.nutrition, f.saturation);
        health = Math.min(maxHealth, health + f.regenHealing());
    }

    /** Take damage of a type with the given exhaustion (0.1 or 0, see EXHAUSTION_DAMAGE). */
    public void damage(float hp, float typeExhaustion) {
        if (hp <= 0) {
            return;
        }
        addExhaustion(typeExhaustion);
        health = Math.max(0, health - hp);
    }

    /** One server tick of FoodData.tick. */
    public void tick() {
        if (exhaustion > EXHAUSTION_DROP) {
            exhaustion -= EXHAUSTION_DROP;
            if (saturation > 0) {
                saturation = Math.max(saturation - 1, 0);
            } else if (difficulty != Difficulty.PEACEFUL) {
                food = Math.max(food - 1, 0);
            }
        }
        if (naturalRegen && saturation > 0 && hurt() && food >= MAX_FOOD) {
            if (++tickTimer >= HEALTH_TICK_COUNT_SATURATED) {
                float spent = Math.min(saturation, EXHAUSTION_HEAL);
                heal(spent / EXHAUSTION_HEAL);
                addExhaustion(spent);
                tickTimer = 0;
            }
        } else if (naturalRegen && food >= HEAL_LEVEL && hurt()) {
            if (++tickTimer >= HEALTH_TICK_COUNT) {
                heal(1);
                addExhaustion(EXHAUSTION_HEAL);
                tickTimer = 0;
            }
        } else if (food <= 0) {
            if (++tickTimer >= HEALTH_TICK_COUNT) {
                if (health > 10 || difficulty == Difficulty.HARD || health > 1 && difficulty == Difficulty.NORMAL) {
                    health -= 1;
                }
                tickTimer = 0;
            }
        } else {
            tickTimer = 0;
        }
    }

    private void heal(float hp) {
        health = Math.min(maxHealth, health + hp);
    }

    /**
     * Exhaustion we can still spend before food drops to 6 and sprinting stops, ignoring regen: every drop past 4 takes
     * a whole saturation point (even a fraction) or a food point.
     */
    public float exhaustionUntilSprintStops() {
        if (!canSprint()) {
            return 0;
        }
        int drops = (int) Math.ceil(saturation) + (food - SPRINT_LEVEL);
        // the n-th drop happens once exhaustion passes 4n (strictly), with what we already carry counted
        return Math.max(0, EXHAUSTION_DROP * drops - exhaustion) + 1e-4F;
    }

    /** Food and saturation as "hunger points" the planner can burn: 4 exhaustion each. */
    public float hungerPoints() {
        return food + saturation;
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A stretch of a planned path: how long it takes and how much exhaustion it adds, spread evenly over it. */
    public static final class Segment {
        public final int ticks;
        public final float exhaustion;
        public final float damage;
        public final float damageExhaustion;

        public Segment(int ticks, float exhaustion) {
            this(ticks, exhaustion, 0, 0);
        }

        public Segment(int ticks, float exhaustion, float damage, float damageExhaustion) {
            this.ticks = Math.max(1, ticks);
            this.exhaustion = exhaustion;
            this.damage = damage;
            this.damageExhaustion = damageExhaustion;
        }

        public static Segment sprint(double metres, int ticks) { return new Segment(ticks, sprintExhaustion(metres)); }
        public static Segment walk(int ticks) { return new Segment(ticks, 0); }
        public static Segment jump(boolean sprinting, int ticks) { return new Segment(ticks, jumpExhaustion(sprinting)); }
        public static Segment mine(int blocks, int ticks) { return new Segment(ticks, mineExhaustion(blocks)); }
        public static Segment swim(double metres, int ticks) { return new Segment(ticks, swimExhaustion(metres)); }
        public static Segment attack(int hits, int ticks) { return new Segment(ticks, attackExhaustion(hits)); }
        public static Segment hurt(float hp, float typeExhaustion) { return new Segment(1, 0, hp, typeExhaustion); }
    }

    public static final class Prediction {
        public int ticks;
        public int endFood;
        public float endSaturation;
        public float endHealth;
        public float minHealth;
        public int minFood;
        /** First tick food is at or below 6 (sprinting stops), -1 if it never is. */
        public int sprintStopsAt = -1;
        /** First tick food is 0, -1 if never. */
        public int starvingAt = -1;
        /** Index of the segment running when sprinting stopped, -1 if never. */
        public int sprintStopsInSegment = -1;
        public float exhaustionAdded;
    }

    /** Run this model forward along the segments (on a copy); this model is left alone. */
    public Prediction predict(List<Segment> segments) {
        HungerModel m = copy();
        Prediction p = new Prediction();
        p.minHealth = m.health;
        p.minFood = m.food;
        if (!m.canSprint()) {
            p.sprintStopsAt = 0;
            p.sprintStopsInSegment = 0;
        }
        if (m.food <= 0) {
            p.starvingAt = 0;
        }
        int t = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.damage > 0) {
                m.damage(s.damage, s.damageExhaustion);
            }
            float per = s.exhaustion / s.ticks;
            p.exhaustionAdded += s.exhaustion;
            for (int k = 0; k < s.ticks; k++) {
                m.addExhaustion(per);
                m.tick();
                t++;
                if (p.sprintStopsAt < 0 && !m.canSprint()) {
                    p.sprintStopsAt = t;
                    p.sprintStopsInSegment = i;
                }
                if (p.starvingAt < 0 && m.food <= 0) {
                    p.starvingAt = t;
                }
                p.minHealth = Math.min(p.minHealth, m.health);
                p.minFood = Math.min(p.minFood, m.food);
            }
        }
        p.ticks = t;
        p.endFood = m.food;
        p.endSaturation = m.saturation;
        p.endHealth = m.health;
        return p;
    }

    /**
     * Ticks of natural regen to get back {@code hp} health from the current state, with no extra exhaustion. -1 if regen
     * stops first (food falls under 18). Mutates nothing.
     */
    public int ticksToHeal(float hp, int maxTicks) {
        if (hp <= 0) {
            return 0;
        }
        HungerModel m = copy();
        float target = Math.min(m.maxHealth, m.health + hp);
        for (int t = 1; t <= maxTicks; t++) {
            m.tick();
            if (m.health >= target - 1e-4F) {
                return t;
            }
            if (m.food < HEAL_LEVEL) {
                return -1;
            }
        }
        return -1;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
