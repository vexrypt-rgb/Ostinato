package baritone.bombard;

/** One command and its observed effect. An impulse observation is never a confirmed hit on the opponent. */
public final class ImpulseAttempt {
    public enum Result { WAITING, IMPULSE_OBSERVED, UNKNOWN, INTERRUPTED }
    private final long sent;
    private final double vx, vz, ix, iz;
    private Result result = Result.WAITING;

    public ImpulseAttempt(long sent, double vx, double vz, double ix, double iz) {
        this.sent = sent;
        this.vx = vx;
        this.vz = vz;
        this.ix = ix;
        this.iz = iz;
    }

    public Result observe(long tick, boolean present, double nowVx, double nowVz) {
        if (result != Result.WAITING) return result;
        if (!present || tick - sent > 12 || tick < sent) return result = Result.UNKNOWN;
        if (tick == sent) return result;
        double length = Math.hypot(ix, iz);
        // Directional change, not the click itself, is evidence. Collisions/desync can leave the result unknown.
        double baseline = length == 0 ? 0 : (vx * ix + vz * iz) / length;
        double air = baseline * Math.pow(TntMechanics.DRAG, tick - sent);
        double ground = baseline * Math.pow(TntMechanics.DRAG * TntMechanics.GROUND_XZ, tick - sent);
        double projection = length == 0 ? 0 : (nowVx * ix + nowVz * iz) / length - Math.max(air, ground);
        if (projection > length * 0.5) result = Result.IMPULSE_OBSERVED;
        return result;
    }

    public Result interrupt() {
        if (result == Result.WAITING) result = Result.INTERRUPTED;
        return result;
    }
    public Result result() { return result; }
}
