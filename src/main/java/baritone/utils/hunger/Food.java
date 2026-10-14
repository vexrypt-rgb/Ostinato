package baritone.utils.hunger;

/**
 * Ostinato code: one food we could eat, as the planner sees it. Vanilla values live in {@link VanillaFoods}; in game the
 * numbers come from the stack's FOOD and CONSUMABLE components, so modded and datapack food is priced the same way.
 */
public final class Food {
    public final String id;
    public final int nutrition;
    /** Saturation points added (FoodProperties.saturation, already nutrition * modifier * 2). */
    public final float saturation;
    public final int eatTicks;
    public final boolean alwaysEdible;
    /** Regeneration amplifier and duration, -1 / 0 for none (golden apples). */
    public final int regenAmplifier;
    public final int regenTicks;
    /** Absorption added, in hp (golden apple 4, enchanted 16). */
    public final float absorption;
    /** May poison us or give Hunger (raw chicken, rotten flesh, spider eye, pufferfish, poisonous potato). */
    public final boolean risky;
    /** Golden apples and the like: kept for emergencies, never picked just to top up food. */
    public final boolean reserve;

    public Food(String id, int nutrition, float saturation, int eatTicks, boolean alwaysEdible,
                int regenAmplifier, int regenTicks, float absorption, boolean risky, boolean reserve) {
        this.id = id;
        this.nutrition = nutrition;
        this.saturation = saturation;
        this.eatTicks = eatTicks;
        this.alwaysEdible = alwaysEdible;
        this.regenAmplifier = regenAmplifier;
        this.regenTicks = regenTicks;
        this.absorption = absorption;
        this.risky = risky;
        this.reserve = reserve;
    }

    public static Food plain(String id, int nutrition, float modifier) {
        return new Food(id, nutrition, HungerModel.saturationByModifier(nutrition, modifier), HungerModel.EAT_TICKS, false, -1, 0, 0, false, false);
    }

    /** Hp healed by the food's own Regeneration effect over its duration (vanilla: 1 hp every 50 >> amp ticks). */
    public float regenHealing() {
        if (regenAmplifier < 0) {
            return 0;
        }
        int interval = 50 >> regenAmplifier;
        return interval > 0 ? regenTicks / interval : regenTicks;
    }

    @Override
    public String toString() {
        return id + "(" + nutrition + "/" + saturation + ", " + eatTicks + "t)";
    }
}
