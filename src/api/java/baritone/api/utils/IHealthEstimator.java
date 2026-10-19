/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.api.utils;

import net.minecraft.world.entity.LivingEntity;

/**
 * Ostinato: estimated health of nearby living entities. Where the server shows health (vanilla syncs it) the
 * estimate is that value with confidence 1; where it hides or fakes it, it is worked out from the hits, potions,
 * apples, effects and totems seen, with a confidence below 1.
 */
public interface IHealthEstimator {
    /** Never null; an entity never seen reads as its synced values with confidence 0. */
    HealthEstimate estimate(LivingEntity entity);

    default float getEstimatedHealth(LivingEntity entity) {
        return estimate(entity).health();
    }

    /** Estimated health plus absorption. */
    default float getEstimatedTotal(LivingEntity entity) {
        return estimate(entity).total();
    }

    default double getConfidence(LivingEntity entity) {
        return estimate(entity).confidence();
    }
}
