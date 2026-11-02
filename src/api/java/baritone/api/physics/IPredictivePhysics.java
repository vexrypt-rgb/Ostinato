/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.api.physics;

import net.minecraft.world.entity.Entity;

/**
 * Ostinato: the predictive physics engine. It ticks bodies forward with vanilla 26.3's server rules over an immutable
 * copy of the nearby world: trajectories, collisions, falls and fall damage, knockback, projectiles, explosions and
 * primed TNT. Every method is for the game (client) thread: that is where the world is read. Simulation runs inline
 * ({@link #predict}) or on the engine's one bounded worker ({@link #submit}).
 */
public interface IPredictivePhysics {

    /**
     * Answer now, on this thread, at the cost of this tick (counted in {@link #telemetry}). Meant for EMERGENCY and
     * COMBAT work that cannot wait a tick; a repeat of a question already answered against the same snapshot is
     * served from the cache.
     */
    PredictionResult predict(PredictionRequest request);

    /** Copy the world it needs now and run on the worker; poll the ticket from later ticks. Never blocks. */
    PredictionTicket submit(PredictionRequest request);

    /**
     * A body read from a live entity, for a request's subject or targets: players and other living entities, primed
     * TNT, falling blocks, arrows, tridents, thrown items and hurting projectiles (fireballs, wither skulls, wind
     * charges). Other entities throw IllegalArgumentException. Bodies other than the local player carry
     * {@link BodyState#CLIENT_VIEW}, and answers about them CLIENT_STATE.
     */
    BodyState capture(Entity entity);

    PhysicsTelemetry telemetry();

    /**
     * Ostinato Presto v4: warm the predictor up once, in the background, over a terrain of its own (no world access),
     * so a consumer's first inline request is not the cold one. Idempotent and cheap to call every tick.
     *
     * @return whether the warm-up has finished (true where there is none to wait for)
     */
    default boolean warmUp() {
        return true;
    }
}
