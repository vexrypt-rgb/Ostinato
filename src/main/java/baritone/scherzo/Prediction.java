/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

/**
 * One opponent's position some ticks ahead, with how wrong it may be.
 *
 * @param x       feet position ahead
 * @param vx      horizontal velocity at the horizon, blocks a tick
 * @param vy      vertical step the body would make after the horizon
 * @param spread  horizontal error to expect, in blocks: the scatter this opponent's path has shown, grown with the horizon
 * @param bound   horizontal radius no player leaves whose velocity changes by at most {@link OpponentModel.Rules#ACCEL}
 *                a tick from its last seen step (knockback breaks it)
 * @param up      how far above {@code y} the body may be (a grounded one may jump)
 * @param down    how far below
 * @param horizon ticks predicted over: the asked-for ticks plus the newest sample's age
 * @param age     ticks since the newest sample
 * @param flags   what lowered the confidence, as the constants below
 */
public record Prediction(double x, double y, double z, double vx, double vy, double vz,
                         double spread, double bound, double up, double down,
                         double horizon, long age, Confidence confidence, int flags) {

    public enum Confidence { HIGH, MEDIUM, LOW }

    public static final int FEW_SAMPLES = 1, STALE = 1 << 1, TURNING = 1 << 2, AIRBORNE = 1 << 3, JUMPING = 1 << 4,
            FLOOR_ASSUMED = 1 << 5, LEDGE = 1 << 6;

    private static final String[] NAMES = {"few", "stale", "turn", "air", "jump", "floor?", "ledge"};

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** The flags as short words, for logs: "-" for none. */
    public String flagNames() {
        if (flags == 0) return "-";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            if ((flags & 1 << i) == 0) continue;
            if (b.length() > 0) b.append(',');
            b.append(NAMES[i]);
        }
        return b.toString();
    }
}
