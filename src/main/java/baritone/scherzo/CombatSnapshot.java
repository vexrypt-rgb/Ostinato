/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

/**
 * The fight as one tick saw it, in plain numbers: what a decision about this tick may read without touching the game.
 * Built once a tick by {@link Scherzo}; immutable.
 *
 * @param tick        the level's game time
 * @param targetId    the opponent's entity id
 * @param eyeX        our eye position
 * @param vx          our own velocity, blocks a tick
 * @param foeX        the opponent's feet as the client shows it
 * @param foeGround   the opponent is on the ground (its synced flag, or no vertical motion for two ticks)
 * @param lagTicks    round trip in ticks as {@code CombatLag} measures it (0 when nothing is known)
 * @param lead        ticks the server is ahead of what we see of this opponent: the round trip plus its interpolation
 * @param charge      our attack charge, 0..1
 * @param foeHurtTime the opponent's hurt flash ticks left
 * @param now         the opponent where the server will have it when our input of this tick arrives; null when unknown
 */
public record CombatSnapshot(long tick, int targetId, boolean foeIsPlayer,
                             double eyeX, double eyeY, double eyeZ, double vx, double vy, double vz,
                             double foeX, double foeY, double foeZ, double foeWidth, double foeHeight, boolean foeGround,
                             double lagTicks, double lead, float charge, int foeHurtTime, Prediction now) {

    /** Our eye to the opponent's feet, horizontally. */
    public double flatDistance() {
        return Math.hypot(foeX - eyeX, foeZ - eyeZ);
    }
}
