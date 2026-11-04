/*
 * Ostinato: new file (Bombard TNT combat). Not part of upstream Baritone.
 */
package baritone.bombard;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;

import java.util.Arrays;
import java.util.List;

/**
 * What the predictive physics engine says one primed TNT will do: when and where it goes off and what that does to
 * each target. Bombard never simulates TNT itself; this is the engine's answer, kept with its confidence and
 * assumptions. Game-free: built from a {@link PredictionResult}.
 *
 * @param ok           the engine answered and the TNT explodes inside what it simulated
 * @param explodeTick  game tick it explodes on (the tick its fuse runs out), or {@code Long.MIN_VALUE}
 * @param damage       health each target loses, in the request's target order (NaN where the engine gave no answer);
 *                     {@code lethal} likewise (false there)
 * @param kx           velocity the blast gives each target ({@code ky}, {@code kz} likewise)
 * @param chain        TNT blocks it would light (each with a 10..29 tick fuse); 0 below HIGH fidelity, where it is not
 *                     worked out
 */
public record BlastForecast(boolean ok, long explodeTick, double x, double y, double z, float power,
                            float[] damage, boolean[] lethal, double[] kx, double[] ky, double[] kz, int chain,
                            String confidence, long assumptions, String why) {

    /** The engine's question for a TNT captured on tick {@code now}: its whole fuse, against these bodies. */
    public static PredictionRequest request(BodyState tnt, List<BodyState> targets, int difficulty, PredictionRequest.Fidelity fidelity,
                                            PredictionRequest.Priority priority) {
        int horizon = Math.min(PredictionRequest.MAX_HORIZON, TntMechanics.ticksLeft(tnt.fuse));
        return PredictionRequest.trajectory(tnt, horizon).targets(targets).difficulty(difficulty)
                .fidelity(fidelity).priority(priority).deadlineIn(50_000_000L).consumer("bombard").build();
    }

    /** The engine's answer to {@link #request} made on tick {@code now}. */
    public static BlastForecast of(PredictionResult r, long now, int targets) {
        String conf = r == null ? "UNKNOWN" : r.confidence.name();
        long as = r == null ? 0 : r.assumptions;
        if (r == null || !r.ok()) return none(conf, as, r == null ? "no answer" : r.status + "/" + r.failure);
        PredictionResult.Explosion e = r.explosion;
        if (e == null) return none(conf, as, r.status == PredictionResult.Status.PARTIAL ? "stopped before the fuse: " + r.failure : "no explosion in the horizon");
        float[] dmg = new float[targets];
        boolean[] lethal = new boolean[targets];
        double[] kx = new double[targets], ky = new double[targets], kz = new double[targets];
        // a target the engine did not answer for is unknown, never unharmed
        Arrays.fill(dmg, Float.NaN);
        Arrays.fill(kx, Double.NaN);
        Arrays.fill(ky, Double.NaN);
        Arrays.fill(kz, Double.NaN);
        for (int i = 0; i < targets && i < e.targets.size(); i++) {
            PredictionResult.Exposure x = e.targets.get(i);
            dmg[i] = x.damage;
            lethal[i] = x.lethal;
            kx[i] = x.kx;
            ky[i] = x.ky;
            kz[i] = x.kz;
        }
        return new BlastForecast(true, now + e.tick, e.x, e.y, e.z, e.power, dmg, lethal, kx, ky, kz, e.chainTnt.length, conf, as, "");
    }

    private static BlastForecast none(String conf, long as, String why) {
        return new BlastForecast(false, Long.MIN_VALUE, Double.NaN, Double.NaN, Double.NaN, Float.NaN, new float[0], new boolean[0],
                new double[0], new double[0], new double[0], 0, conf, as, why);
    }

    public boolean has(PredictionResult.Assumption a) {
        return (assumptions & a.bit()) != 0;
    }

    /** Health target {@code i} loses, or NaN when unknown. */
    public float damageTo(int i) {
        return ok && i >= 0 && i < damage.length ? damage[i] : Float.NaN;
    }
}
