package baritone.bombard;

import baritone.api.physics.BodyState;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionRequest.Priority;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionResult.Assumption;
import baritone.api.physics.PredictionResult.Failure;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class BlastForecastTest {

    private static PredictionResult.Explosion blast(int tick, PredictionResult.Exposure... targets) {
        return new PredictionResult.Explosion(tick, 1, 64.06, 2, 4, List.of(targets), null, null, new long[]{5, 6});
    }

    @Test
    public void noAnswerIsNotAForecast() {
        BlastForecast f = BlastForecast.of(null, 100, 1);
        assertFalse(f.ok());
        assertEquals(Long.MIN_VALUE, f.explodeTick());
        assertEquals("UNKNOWN", f.confidence());
        assertEquals("no answer", f.why());
        assertTrue(Float.isNaN(f.damageTo(0)));
    }

    @Test
    public void aFailedOrEmptyAnswerSaysWhy() {
        BlastForecast failed = BlastForecast.of(new PredictionResult.Builder(Fidelity.SCREENING).fail(Failure.DEADLINE).build(), 100, 1);
        assertFalse(failed.ok());
        assertTrue(failed.why(), failed.why().contains("DEADLINE"));
        BlastForecast none = BlastForecast.of(new PredictionResult.Builder(Fidelity.SCREENING).horizon(80).build(), 100, 1);
        assertFalse(none.ok());
        assertEquals("no explosion in the horizon", none.why());
        BlastForecast cut = BlastForecast.of(new PredictionResult.Builder(Fidelity.SCREENING).partial(Failure.OUT_OF_SNAPSHOT).build(), 100, 1);
        assertFalse(cut.ok());
        assertTrue(cut.why(), cut.why().startsWith("stopped before the fuse"));
    }

    @Test
    public void anExplosionIsDatedFromTheQuestion() {
        PredictionResult r = new PredictionResult.Builder(Fidelity.HIGH).horizon(40).explosion(blast(40,
                new PredictionResult.Exposure(1, 12.5f, false, 0.1, 0.2, 0.3),
                new PredictionResult.Exposure(0.5f, 30, true, -1, 0, 1))).build();
        BlastForecast f = BlastForecast.of(r, 100, 2);
        assertTrue(f.ok());
        assertEquals(140, f.explodeTick());
        assertEquals(64.06, f.y(), 0);
        assertEquals(4, f.power(), 0);
        assertEquals(12.5f, f.damageTo(0), 0);
        assertFalse(f.lethal()[0]);
        assertTrue(f.lethal()[1]);
        assertEquals(0.3, f.kz()[0], 0);
        assertEquals(2, f.chain());
        assertEquals("EXACT", f.confidence());
    }

    @Test
    public void aTargetTheEngineDidNotPriceIsUnknownNotUnharmed() {
        PredictionResult r = new PredictionResult.Builder(Fidelity.SCREENING).horizon(40)
                .explosion(blast(40, new PredictionResult.Exposure(1, 3, false, 0, 0, 0))).build();
        BlastForecast f = BlastForecast.of(r, 0, 2);
        assertEquals(3, f.damageTo(0), 0);
        assertTrue(Float.isNaN(f.damageTo(1)));
        assertTrue(Double.isNaN(f.kx()[1]));
        assertFalse(f.lethal()[1]);
    }

    @Test
    public void assumptionsCarryThrough() {
        PredictionResult r = new PredictionResult.Builder(Fidelity.SCREENING).horizon(40).assume(Assumption.TARGETS_FROZEN)
                .assume(Assumption.TNT_POWER_ASSUMED).explosion(blast(40)).build();
        BlastForecast f = BlastForecast.of(r, 0, 0);
        assertTrue(f.ok());
        assertTrue(f.has(Assumption.TARGETS_FROZEN));
        assertTrue(f.has(Assumption.TNT_POWER_ASSUMED));
        assertFalse(f.has(Assumption.RANDOM_FUSE));
        assertEquals("APPROXIMATE", f.confidence());
    }

    @Test
    public void theQuestionCoversTheWholeFuseAndNoMore() {
        BodyState me = BodyState.builder(BodyState.Type.PLAYER).pos(3.5, 64, 0.5).build();
        for (int[] c : new int[][]{{30, 30}, {0, 1}, {-2, 1}, {5000, PredictionRequest.MAX_HORIZON}}) {
            BodyState tnt = BodyState.builder(BodyState.Type.PRIMED_TNT).pos(0.5, 64, 0.5).fuse(c[0], 4).build();
            PredictionRequest q = BlastForecast.request(tnt, List.of(me), 2, Fidelity.SCREENING, Priority.PLANNING);
            assertEquals(c[1], q.horizonTicks);
            assertEquals(PredictionRequest.Kind.TRAJECTORY, q.kind);
            assertEquals(1, q.targets.size());
            assertEquals(2, q.difficulty);
            assertEquals("bombard", q.consumer);
            assertTrue(q.deadlineNanos > 0);
        }
    }
}
