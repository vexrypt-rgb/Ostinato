package baritone.pathing.travel;

import baritone.api.movement.MovementBackendKind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: the three-way order, with Baritone movement last. */
public class TravelStackTest {

    @Test
    public void autoIsKinematicThenPhysicsThenSimThenBackup() {
        List<TravelStack.Layer> order = TravelStack.order(MovementBackendKind.AUTO, true, true);
        assertEquals(List.of(
                TravelStack.Layer.KINEMATIC,
                TravelStack.Layer.PHYSICS,
                TravelStack.Layer.SIM,
                TravelStack.Layer.BARITONE), order);
    }

    @Test
    public void switchesDropALayerWithoutReorderingTheRest() {
        assertEquals(List.of(TravelStack.Layer.PHYSICS, TravelStack.Layer.SIM, TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.AUTO, false, true));
        assertEquals(List.of(TravelStack.Layer.KINEMATIC, TravelStack.Layer.PHYSICS, TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.AUTO, true, false));
    }

    @Test
    public void namedPreferencesPinOneLayerThenTheBackup() {
        assertEquals(List.of(TravelStack.Layer.KINEMATIC, TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.KINEMATIC, true, true));
        assertEquals(List.of(TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.KINEMATIC, false, true));
        assertEquals(List.of(TravelStack.Layer.PHYSICS, TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.PHYSICS, false, false));
        assertEquals(List.of(TravelStack.Layer.SIM, TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.SIM, false, true));
        assertEquals(List.of(TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.SIM, true, false));
        assertEquals(List.of(TravelStack.Layer.BARITONE),
                TravelStack.order(MovementBackendKind.BARITONE, true, true));
    }

    @Test
    public void settingTextMapsOntoThePreferenceHook() {
        assertEquals(MovementBackendKind.AUTO, TravelStack.preference(null));
        assertEquals(MovementBackendKind.AUTO, TravelStack.preference(""));
        assertEquals(MovementBackendKind.AUTO, TravelStack.preference("whatever"));
        assertEquals(MovementBackendKind.KINEMATIC, TravelStack.preference("kin"));
        assertEquals(MovementBackendKind.PHYSICS, TravelStack.preference("phys"));
        assertEquals(MovementBackendKind.SIM, TravelStack.preference("tas"));
        assertEquals(MovementBackendKind.BARITONE, TravelStack.preference("backup"));
    }

    @Test
    public void aSolvedLayerDoesNotUseTheBackup() {
        assertTrue(TravelStack.backupUnused(TravelStack.Layer.KINEMATIC));
        assertTrue(TravelStack.backupUnused(TravelStack.Layer.PHYSICS));
        assertTrue(TravelStack.backupUnused(TravelStack.Layer.SIM));
        assertFalse(TravelStack.backupUnused(TravelStack.Layer.BARITONE));
        assertFalse(TravelStack.backupUnused(null));
    }
}
