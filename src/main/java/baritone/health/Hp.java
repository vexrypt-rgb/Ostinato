/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.health;

import baritone.api.BaritoneAPI;
import baritone.api.utils.HealthEstimate;
import net.minecraft.world.entity.LivingEntity;

/**
 * Ostinato: the health combat and mob fighting go by. With {@code healthEstimator} on it is the estimator's (exact
 * where the server shows health); off, what the entity reports. {@link #upper} is for decisions that must not be taken
 * on a guess (finishing blows): the estimate plus one standard deviation.
 */
public final class Hp {
    private Hp() {}

    public static HealthEstimate of(LivingEntity e) {
        if (!BaritoneAPI.getSettings().healthEstimator.value) {
            return new HealthEstimate(e.getHealth(), e.getAbsorptionAmount(), e.getMaxHealth(), 1, 0, "synced");
        }
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone().getHealthEstimator().estimate(e);
        } catch (RuntimeException | LinkageError noGame) { // unit tests: no game, no estimator
            return new HealthEstimate(e.getHealth(), e.getAbsorptionAmount(), e.getMaxHealth(), 1, 0, "synced");
        }
    }

    /** Health plus absorption. */
    public static float total(LivingEntity e) {
        return of(e).total();
    }

    /** Health alone. */
    public static float health(LivingEntity e) {
        return of(e).health();
    }

    /** Health plus absorption, one standard deviation high. */
    public static float upper(LivingEntity e) {
        HealthEstimate h = of(e);
        return (float) (h.total() + h.sd());
    }
}
