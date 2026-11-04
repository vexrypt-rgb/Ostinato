package baritone.scherzo;

/** Five bounded, caller-validated movement candidates; minimize worst predicted error from the reach band. */
public final class ManeuverChoice {
    private ManeuverChoice() {}
    public record Candidate(int forward, int side, double x, double z, boolean feasible) {}
    public static Candidate choose(Prediction target, double minimum, double maximum, Candidate... candidates) {
        if (target == null || target.age() != 0 || target.confidence() == Prediction.Confidence.LOW
                || !Double.isFinite(target.spread()) || minimum < 0 || maximum < minimum) return null;
        Candidate best = null;
        double error = Double.POSITIVE_INFINITY;
        for (Candidate c : candidates) {
            if (!c.feasible()) continue;
            double distance = Math.hypot(c.x() - target.x(), c.z() - target.z());
            double miss = Math.max(Math.max(0, minimum - Math.max(0, distance - target.spread())),
                    Math.max(0, distance + target.spread() - maximum));
            if (miss < error) { error = miss; best = c; }
        }
        return best;
    }
}
