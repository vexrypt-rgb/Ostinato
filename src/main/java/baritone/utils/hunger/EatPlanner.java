package baritone.utils.hunger;

import java.util.List;

/**
 * Ostinato code: which food to eat and when, and what a hit point of damage is worth in ticks.
 * <p>
 * Eating stands us still for the eat time (32 ticks, kelp 16) and slows us to a crawl, so it goes in pauses the bot
 * already has (waiting on a path search, on a ladder, in a long fall, a long dig, idle) unless it is urgent.
 */
public final class EatPlanner {
    private EatPlanner() {}

    public enum Pause { NONE, PATH_SEARCH, LADDER, FALLING, MINING, IDLE }

    /** Ticks we are worth spending per hunger point we have to re-buy without any food on us. */
    public static final double NO_FOOD_TICKS_PER_POINT = 60;
    /** Regen that cannot happen (food under 18, nothing to eat): ticks charged per hp. */
    public static final double NO_REGEN_TICKS_PER_HP = 400;
    public static final double INFINITY = 1e6;

    public static final class Decision {
        public final Food food;
        public final boolean eat;
        /** True when eating is worth stopping for. */
        public final boolean urgent;
        public final String reason;

        Decision(Food food, boolean eat, boolean urgent, String reason) {
            this.food = food;
            this.eat = eat;
            this.urgent = urgent;
            this.reason = reason;
        }

        static Decision no(String why) {
            return new Decision(null, false, false, why);
        }
    }

    /** Food we can eat right now (vanilla blocks eating at 20 food unless always edible). */
    public static boolean edible(HungerModel m, Food f) {
        return f.alwaysEdible || m.food < HungerModel.MAX_FOOD;
    }

    /** Nutrition that would be lost past 20. */
    public static int waste(HungerModel m, Food f) {
        return Math.max(0, f.nutrition - (HungerModel.MAX_FOOD - m.food));
    }

    /** Hunger points (food + saturation) this food actually adds now. */
    public static float gain(HungerModel m, Food f) {
        int n = Math.min(f.nutrition, HungerModel.MAX_FOOD - m.food);
        int newFood = m.food + n;
        float s = Math.max(0, Math.min(m.saturation + f.saturation, newFood) - m.saturation);
        return n + s;
    }

    /**
     * Prefer what adds the most per tick of eating, saturation counting as much as food (both are 4 exhaustion), minus
     * wasted nutrition, risky food last; golden apples only when health is the problem.
     */
    public static double score(HungerModel m, Food f, boolean healthEmergency) {
        if (!edible(m, f) || f.reserve && !healthEmergency) {
            return Double.NEGATIVE_INFINITY;
        }
        double value = gain(m, f);
        if (healthEmergency) {
            value += 3 * f.regenHealing() + f.absorption;
        }
        double perEat = value / (f.eatTicks / (double) HungerModel.EAT_TICKS);
        return perEat - waste(m, f) - (f.risky ? 6 : 0);
    }

    public static Food choose(HungerModel m, List<Food> foods, boolean healthEmergency) {
        Food best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Food f : foods) {
            double s = score(m, f, healthEmergency);
            if (s > bestScore) {
                bestScore = s;
                best = f;
            }
        }
        return bestScore == Double.NEGATIVE_INFINITY ? null : best;
    }

    /**
     * @param pause            what the bot is doing
     * @param pauseTicks       how long that pause should still last, -1 unknown
     * @param ticksToSprintStop from {@link HungerModel#predict}: when the route would drop us to 6 food, -1 never
     * @param healthFloor      never plan below this (half hearts)
     */
    public static Decision decide(HungerModel m, List<Food> foods, Pause pause, int pauseTicks, int ticksToSprintStop, double healthFloor) {
        boolean lowHealth = m.health <= healthFloor;
        Food f = choose(m, foods, lowHealth);
        if (f == null) {
            return Decision.no(foods.isEmpty() ? "no food" : "nothing edible");
        }
        // urgent: stop for it
        if (lowHealth && (m.food < HungerModel.HEAL_LEVEL + 2 || f.reserve)) {
            return new Decision(f, true, true, "health at floor");
        }
        if (m.food <= 4) {
            return new Decision(f, true, true, "food " + m.food);
        }
        if (!m.canSprint() || ticksToSprintStop >= 0 && ticksToSprintStop <= 200 && pause == Pause.NONE) {
            return new Decision(f, true, true, "sprint stops in " + Math.max(0, ticksToSprintStop) + "t");
        }
        if (pause == Pause.NONE) {
            return Decision.no("no pause");
        }
        boolean longEnough = pauseTicks < 0 ? pause != Pause.MINING : pauseTicks >= f.eatTicks / 2;
        if (!longEnough) {
            return Decision.no("pause too short");
        }
        boolean noWaste = waste(m, f) == 0;
        boolean wantRegen = m.hurt() && m.food < HungerModel.MAX_FOOD;
        boolean routeNeeds = ticksToSprintStop >= 0;
        if (noWaste || wantRegen || routeNeeds) {
            return new Decision(f, true, false, pause + (noWaste ? " no waste" : wantRegen ? " regen" : " route"));
        }
        return Decision.no("would waste " + waste(m, f));
    }

    /**
     * Ticks a planned {@code hp} of damage is worth: the hunger regen spends on it (6 exhaustion = 1.5 hunger points per
     * hp), re-bought at the best food's eat time, plus {@code exposureWeight} per tick we spend below full health while
     * regen works (saturation-fast 10 ticks/hp at 20 food, 80 ticks/hp at 18-19). Damage that would leave us under the
     * floor is {@link #INFINITY}.
     */
    public static double damageCostTicks(HungerModel m, float hp, float typeExhaustion, Food best, double exposureWeight, double healthFloor) {
        if (hp <= 0) {
            return 0;
        }
        if (m.health - hp < healthFloor) {
            return INFINITY;
        }
        HungerModel after = m.copy();
        after.damage(hp, typeExhaustion);
        double points = hp * HungerModel.EXHAUSTION_HEAL / HungerModel.EXHAUSTION_DROP + typeExhaustion / HungerModel.EXHAUSTION_DROP;
        double foodTicks;
        if (best != null) {
            foodTicks = points * best.eatTicks / Math.max(0.5, best.nutrition + best.saturation);
        } else {
            foodTicks = points * NO_FOOD_TICKS_PER_POINT;
        }
        if (after.food < HungerModel.HEAL_LEVEL && best != null) {
            after.eat(best);
        }
        int regen = after.ticksToHeal(hp, 20 * 60 * 5);
        double regenTicks = regen >= 0 ? regen : hp * NO_REGEN_TICKS_PER_HP;
        return foodTicks + regenTicks * exposureWeight;
    }

    /** The drop-and-eat versus safe-route call, as A* makes it: take the damage if its route plus its price is shorter. */
    public static boolean takeDamageRoute(double damageRouteTicks, double safeRouteTicks, HungerModel m, float hp, float typeExhaustion, Food best, double exposureWeight, double healthFloor) {
        return damageRouteTicks + damageCostTicks(m, hp, typeExhaustion, best, exposureWeight, healthFloor) < safeRouteTicks;
    }
}
