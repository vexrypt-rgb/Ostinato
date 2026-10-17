package baritone.movement;

import baritone.api.movement.MovementBackendKind;
import baritone.api.movement.MovementFailureReason;
import baritone.api.movement.MovementStatus;
import baritone.api.movement.PathResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PathResultTest {

    @Test
    public void pathResultFactories() {
        PathResult ok = PathResult.accepted(MovementBackendKind.BARITONE, MovementStatus.PATHING);
        assertTrue(ok.isAccepted());
        assertEquals(MovementFailureReason.NONE, ok.getFailure());

        PathResult bad = PathResult.failed(MovementFailureReason.DECLINED, MovementBackendKind.BARITONE, "no");
        assertFalse(bad.isAccepted());
        assertEquals(MovementFailureReason.DECLINED, bad.getFailure());
    }

    /** TenorClef reaches these by reflection; they have to stay. */
    @Test
    public void tenorClefEntryPointsStay() throws Exception {
        assertEquals(MovementBackendKind.BARITONE, MovementBackends.preference());
        MovementBackends.class.getMethod("engine", baritone.api.IBaritone.class);
        Class<?> engine = baritone.api.movement.IMovementEngine.class;
        engine.getMethod("goTo", baritone.api.movement.MovementGoal.class);
        engine.getMethod("goTo", net.minecraft.core.BlockPos.class);
        engine.getMethod("follow", net.minecraft.world.entity.Entity.class, double.class);
        engine.getMethod("isPathing");
        engine.getMethod("cancel");
        engine.getMethod("statusLine");
        baritone.api.movement.MovementGoal.class.getMethod("custom", baritone.api.pathing.goals.Goal.class);
        PathResult.class.getMethod("isAccepted");
        PathResult.class.getMethod("getBackend");
    }
}
