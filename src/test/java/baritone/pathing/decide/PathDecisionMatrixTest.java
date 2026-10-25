package baritone.pathing.decide;

import baritone.pathing.decide.PathDecisionMatrix.Action;
import baritone.pathing.decide.PathDecisionMatrix.Category;
import baritone.pathing.decide.PathDecisionMatrix.Dist;
import baritone.pathing.decide.PathDecisionMatrix.Terrain;
import org.junit.Test;

import static org.junit.Assert.*;

// Ostinato: the path decision matrix picks, refuses thin rows, and survives export/load
public class PathDecisionMatrixTest {
    private static final Category HF = new Category(Terrain.HILLS, Dist.FAR);

    private static PathDecisionMatrix trained() {
        PathDecisionMatrix m = new PathDecisionMatrix();
        m.minSamples = 2;
        for (Action a : Action.values()) for (int i = 0; i < 2; i++) m.record(HF, a, a == Action.WA3 ? 100 : 200 + i);
        return m;
    }

    @Test
    public void picksLowestMean() {
        assertEquals(Action.WA3, trained().choose(HF));
    }

    @Test
    public void thinOrUnknownRowHasNoOpinion() {
        PathDecisionMatrix m = new PathDecisionMatrix();
        m.minSamples = 2;
        m.record(HF, Action.ASTAR, 1);
        assertNull(m.choose(HF));
        assertNull(trained().choose(new Category(Terrain.FLAT, Dist.NEAR)));
    }

    @Test
    public void exportLoadRoundTrip() {
        PathDecisionMatrix back = PathDecisionMatrix.parse(trained().export() + "HILLS/FAR\tNOT_A_BACKEND\t1\t9\n");
        back.minSamples = 2;
        assertEquals(Action.WA3, back.choose(HF));
        assertEquals(200.5, back.mean(HF, Action.ASTAR), 1e-9);
    }

    @Test
    public void distanceBuckets() {
        assertEquals(Dist.NEAR, Dist.of(10));
        assertEquals(Dist.MID, Dist.of(100));
        assertEquals(Dist.FAR, Dist.of(400));
    }

    @Test
    public void failureScoresWorseThanAnyRealPlan() {
        assertTrue(PathDecisionMatrix.score(false, 0, 0) > PathDecisionMatrix.score(true, 5000, 10_000));
    }
}
