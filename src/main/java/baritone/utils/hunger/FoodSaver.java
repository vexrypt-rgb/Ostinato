package baritone.utils.hunger;

/**
 * Ostinato code: emergency food-saving mode. When the food we have and carry cannot cover the route (or there is none),
 * stop sprinting and sprint-jumping, walk and jump plainly, skip jumps, digs and attacks we don't need, so the hunger
 * lasts. The planner switches to walking costs while it is on. Hysteresis: it stays on until food is back to
 * {@link #exitFood} and the route is covered.
 */
public final class FoodSaver {
    public int enterFood = 10;
    public int exitFood = 14;
    private boolean on;
    private String reason = "";

    public boolean on() {
        return on;
    }

    public String reason() {
        return reason;
    }

    /**
     * @param prediction    the current route's prediction at normal (sprinting) costs, null without a path
     * @param carriedPoints hunger points (nutrition + saturation) of the food we carry, golden apples excluded
     * @param manual        the foodSavingMode setting
     */
    public boolean update(HungerModel m, HungerModel.Prediction prediction, float carriedPoints, boolean manual) {
        String why = null;
        if (manual) {
            why = "manual";
        } else if (m.food <= HungerModel.SPRINT_LEVEL && carriedPoints <= 0) {
            why = "food " + m.food + ", none carried";
        } else if (prediction != null && prediction.starvingAt >= 0 && carriedPoints < 1) {
            why = "route starves at " + prediction.starvingAt + "t";
        } else if (prediction != null && prediction.sprintStopsAt >= 0 && carriedPoints < deficit(m, prediction)) {
            why = "route drops food to 6 at " + prediction.sprintStopsAt + "t, too little carried";
        } else if (m.food <= enterFood && carriedPoints <= 0) {
            why = "food " + m.food + ", none carried";
        }
        if (why != null) {
            on = true;
            reason = why;
        } else if (on) {
            boolean restored = m.food >= exitFood && (prediction == null || prediction.sprintStopsAt < 0 || carriedPoints > 0);
            if (restored) {
                on = false;
                reason = "restored, food " + m.food;
            }
        }
        return on;
    }

    /** Hunger points the route burns beyond what we can spend before sprinting stops. */
    public static float deficit(HungerModel m, HungerModel.Prediction prediction) {
        float burn = (prediction.exhaustionAdded + m.exhaustion) / HungerModel.EXHAUSTION_DROP;
        float spendable = Math.max(0, m.food - HungerModel.SPRINT_LEVEL) + m.saturation;
        return Math.max(0, burn - spendable);
    }

    /**
     * Should we swing at all? A hit costs 0.1 exhaustion and a miss costs the swing's time, so in food-saving mode only
     * needed attacks with a near-certain hit (in reach, crosshair on it, cooldown charged) go ahead.
     */
    public static boolean allowAttack(boolean foodSaving, boolean needed, double hitChance) {
        if (!foodSaving) {
            return true;
        }
        return needed && hitChance >= 0.9;
    }
}
