package baritone.movement;

import baritone.api.movement.MovementBackendKind;
import baritone.api.movement.MovementBackendSelector;
import baritone.api.movement.MovementFailureReason;
import baritone.api.movement.PathResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementBackendSelectorTest {

    @Test
    public void autoPrefersTungstenWhenAvailable() {
        assertEquals(MovementBackendKind.TUNGSTEN,
                MovementBackendSelector.select(MovementBackendKind.AUTO, true));
    }

    @Test
    public void autoFallsBackToBaritoneWhenTungstenMissing() {
        assertEquals(MovementBackendKind.BARITONE,
                MovementBackendSelector.select(MovementBackendKind.AUTO, false));
    }

    @Test
    public void forcedBaritoneIgnoresTungsten() {
        assertEquals(MovementBackendKind.BARITONE,
                MovementBackendSelector.select(MovementBackendKind.BARITONE, true));
    }

    @Test
    public void forcedTungstenFallsBackWhenMissing() {
        assertEquals(MovementBackendKind.BARITONE,
                MovementBackendSelector.select(MovementBackendKind.TUNGSTEN, false));
    }

    @Test
    public void forcedTungstenUsesTungstenWhenPresent() {
        assertEquals(MovementBackendKind.TUNGSTEN,
                MovementBackendSelector.select(MovementBackendKind.TUNGSTEN, true));
    }

    @Test
    public void nullPreferenceTreatedAsAuto() {
        assertEquals(MovementBackendKind.BARITONE,
                MovementBackendSelector.select(null, false));
        assertEquals(MovementBackendKind.TUNGSTEN,
                MovementBackendSelector.select(null, true));
    }

    @Test
    public void failureMapping() {
        assertEquals(MovementFailureReason.BACKEND_UNAVAILABLE,
                MovementBackendSelector.mapFailure(false, true));
        assertEquals(MovementFailureReason.DECLINED,
                MovementBackendSelector.mapFailure(true, true));
        assertEquals(MovementFailureReason.PATH_CALC_FAILED,
                MovementBackendSelector.mapFailure(true, false));
    }

    @Test
    public void pathResultFactories() {
        PathResult ok = PathResult.accepted(MovementBackendKind.BARITONE, baritone.api.movement.MovementStatus.PATHING);
        assertTrue(ok.isAccepted());
        assertEquals(MovementFailureReason.NONE, ok.getFailure());

        PathResult bad = PathResult.failed(MovementFailureReason.DECLINED, MovementBackendKind.TUNGSTEN, "no");
        assertFalse(bad.isAccepted());
        assertEquals(MovementFailureReason.DECLINED, bad.getFailure());
    }
}
