/*
 * Ostinato: new file (health estimator). Not part of upstream Baritone.
 */
package baritone.api.utils;

/**
 * Ostinato: what the health estimator believes about one entity.
 *
 * @param health     estimated health points (not counting absorption)
 * @param absorption estimated absorption points
 * @param maxHealth  maximum health
 * @param confidence 1 for an exact reading, toward 0 as the estimate's uncertainty grows ({@code exp(-sd/3)})
 * @param sd         standard deviation of {@code health}, in points
 * @param source     where it came from: {@code synced}, {@code scoreboard}, {@code bossbar}, {@code inferred} or {@code none}
 */
public record HealthEstimate(float health, float absorption, float maxHealth, double confidence, double sd, String source) {
    /** Health plus absorption: what a hit has to get through. */
    public float total() {
        return health + absorption;
    }
}
