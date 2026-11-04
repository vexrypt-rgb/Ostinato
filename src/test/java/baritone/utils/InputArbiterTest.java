/*
 * Ostinato: input lease tests. No client.
 */
package baritone.utils;

import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.FallInput;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InputArbiterTest {

    @Test
    public void staleEpochIsRejectedAndDoesNotSeal() {
        InputArbiter arb = new InputArbiter();
        InputArbiter.Lease owner = arb.claim("walker", 1, false, InputArbiter.Resource.MOVE);
        long first = owner.epoch();
        assertEquals(InputArbiter.Submit.OK, arb.submit("walker", first, down(Input.MOVE_BACK)));
        arb.claim("walker", 1, false, InputArbiter.Resource.MOVE);
        assertNotEquals(first, owner.epoch());
        assertEquals(InputArbiter.Submit.STALE, arb.submit("walker", first, down(Input.MOVE_FORWARD)));
        assertEquals(InputArbiter.Submit.OK, arb.submit("walker", owner.epoch(), down()));
        assertDown(arb.seal());
        assertEquals(InputArbiter.Submit.STALE, arb.submit("missing", first, down(Input.MOVE_FORWARD)));
    }

    @Test
    public void simultaneousFramesMergeAndPriorityIgnoresClaimOrder() {
        InputArbiter both = new InputArbiter();
        InputArbiter.Lease move = both.claim("move", 1, false, InputArbiter.Resource.MOVE);
        InputArbiter.Lease jump = both.claim("jump", 1, false, InputArbiter.Resource.JUMP);
        assertEquals(InputArbiter.Submit.OK, both.submit("jump", jump.epoch(), down(Input.JUMP)));
        assertEquals(InputArbiter.Submit.OK, both.submit("move", move.epoch(), down(Input.MOVE_FORWARD, Input.SPRINT)));
        assertDown(both.seal(), Input.MOVE_FORWARD, Input.SPRINT, Input.JUMP);

        for (boolean alphaFirst : new boolean[]{true, false}) {
            InputArbiter arb = new InputArbiter();
            InputArbiter.Lease alpha;
            InputArbiter.Lease zeta;
            if (alphaFirst) {
                alpha = arb.claim("alpha", 3, false, InputArbiter.Resource.MOVE);
                zeta = arb.claim("zeta", 3, false, InputArbiter.Resource.MOVE);
            } else {
                zeta = arb.claim("zeta", 3, false, InputArbiter.Resource.MOVE);
                long before = zeta.epoch();
                alpha = arb.claim("alpha", 3, false, InputArbiter.Resource.MOVE);
                assertEquals(InputArbiter.Submit.STALE, arb.submit("zeta", before, down(Input.MOVE_BACK)));
            }
            assertTrue(alpha.holds(InputArbiter.Resource.MOVE));
            assertFalse(zeta.alive());
            assertEquals(InputArbiter.Submit.NO_LEASE, arb.submit("zeta", zeta.epoch(), down(Input.MOVE_BACK)));
            assertEquals(InputArbiter.Submit.OK, arb.submit("alpha", alpha.epoch(), down(Input.MOVE_FORWARD)));
            assertDown(arb.seal(), Input.MOVE_FORWARD);
        }

        for (boolean lowFirst : new boolean[]{true, false}) {
            InputArbiter arb = new InputArbiter();
            InputArbiter.Lease low;
            InputArbiter.Lease high;
            if (lowFirst) {
                low = arb.claim("alpha", 1, false, InputArbiter.Resource.MOVE);
                high = arb.claim("zebra", 2, false, InputArbiter.Resource.MOVE);
            } else {
                high = arb.claim("zebra", 2, false, InputArbiter.Resource.MOVE);
                low = arb.claim("alpha", 1, false, InputArbiter.Resource.MOVE);
            }
            assertTrue(high.holds(InputArbiter.Resource.MOVE));
            assertFalse(low.alive());
            assertEquals(InputArbiter.Submit.NO_LEASE, arb.submit("alpha", low.epoch(), down(Input.MOVE_FORWARD)));
        }
    }

    @Test
    public void jumpInterruptionClearsOnlyJump() {
        InputArbiter arb = new InputArbiter();
        InputArbiter.Lease steer = arb.claim("steer", 10, false, InputArbiter.Resource.MOVE, InputArbiter.Resource.JUMP);
        long epoch = steer.epoch();
        assertEquals(InputArbiter.Submit.OK, arb.submit("steer", epoch, down(Input.MOVE_FORWARD, Input.JUMP)));
        assertDown(arb.seal(), Input.MOVE_FORWARD, Input.JUMP);

        InputArbiter.Lease brake = arb.claim("brake", 0, true, InputArbiter.Resource.JUMP);
        assertEquals(epoch, steer.epoch());
        assertTrue(steer.holds(InputArbiter.Resource.MOVE));
        assertFalse(steer.holds(InputArbiter.Resource.JUMP));
        assertTrue(brake.holds(InputArbiter.Resource.JUMP));
        assertFalse(arb.applied().get(Input.JUMP));
        assertTrue(arb.applied().get(Input.MOVE_FORWARD));

        assertEquals(InputArbiter.Submit.OK, arb.submit("brake", brake.epoch(), down()));
        assertEquals(InputArbiter.Submit.OK, arb.submit("steer", epoch, down(Input.MOVE_FORWARD, Input.JUMP)));
        assertDown(arb.seal(), Input.MOVE_FORWARD);
    }

    @Test
    public void cancelRequestHoldsKeysUntilRelease() {
        InputArbiter arb = new InputArbiter();
        InputArbiter.Lease owner = arb.claim("fall", 10, false,
                InputArbiter.Resource.MOVE, InputArbiter.Resource.JUMP, InputArbiter.Resource.SNEAK);
        long epoch = owner.epoch();
        assertEquals(InputArbiter.Submit.OK, arb.submit("fall", epoch,
                down(Input.MOVE_FORWARD, Input.JUMP, Input.SNEAK)));
        assertDown(arb.seal(), Input.MOVE_FORWARD, Input.JUMP, Input.SNEAK);

        arb.requestCancel("fall");
        assertTrue(owner.cancelRequested());
        assertTrue(owner.holds(InputArbiter.Resource.MOVE));
        assertEquals(epoch, owner.epoch());
        assertTrue(arb.applied().get(Input.MOVE_FORWARD));
        assertEquals(InputArbiter.Submit.OK, arb.submit("fall", epoch, down(Input.MOVE_FORWARD, Input.JUMP, Input.SNEAK)));
        assertDown(arb.seal(), Input.MOVE_FORWARD, Input.JUMP, Input.SNEAK);

        arb.confirmRelease("fall");
        assertFalse(owner.holds(InputArbiter.Resource.MOVE));
        assertFalse(owner.holds(InputArbiter.Resource.JUMP));
        assertFalse(owner.holds(InputArbiter.Resource.SNEAK));
        assertEquals(InputArbiter.End.RELEASED, owner.end());
        assertTrue(owner.cancelRequested());
        assertDown(arb.applied());
        assertEquals(InputArbiter.Submit.STALE, arb.submit("fall", epoch, down(Input.MOVE_FORWARD)));
    }

    @Test
    public void controllerFailureClearsMovementWithoutCancelling() {
        InputArbiter arb = new InputArbiter();
        InputArbiter.Lease owner = arb.claim("pilot", 4, false, InputArbiter.Resource.MOVE, InputArbiter.Resource.JUMP);
        long epoch = owner.epoch();
        assertEquals(InputArbiter.Submit.OK, arb.submit("pilot", epoch, down(Input.MOVE_FORWARD, Input.SPRINT, Input.JUMP)));
        assertDown(arb.seal(), Input.MOVE_FORWARD, Input.SPRINT, Input.JUMP);
        arb.fail("pilot");
        assertFalse(owner.cancelRequested());
        assertEquals(InputArbiter.End.FAILED, owner.end());
        assertFalse(owner.alive());
        assertDown(arb.applied());
        assertEquals(InputArbiter.Submit.STALE, arb.submit("pilot", epoch, down(Input.MOVE_FORWARD)));

        InputArbiter fall = new InputArbiter();
        InputArbiter.Lease lease = fall.claim(FallInput.OWNER, FallInput.PRIORITY, false, FallInput.LEASE);
        long fallEpoch = lease.epoch();
        Map<Input, Boolean> chord = FallInput.chord(true, false, false, false, true, true, false);
        assertNotNull(FallInput.submit(fall, lease, chord));
        Map<Input, Boolean> ended = FallInput.end(fall, lease, true);
        assertNotNull(ended);
        assertDown(ended);
        assertEquals(InputArbiter.End.FAILED, lease.end());
        assertFalse(lease.cancelRequested());
        assertEquals(InputArbiter.Submit.STALE, fall.submit(FallInput.OWNER, fallEpoch, chord));
        assertNull(FallInput.end(fall, lease, true));
    }

    @Test
    public void emergencyPreemptsHigherPriorityAndDropsResiduals() {
        for (boolean zebraFirst : new boolean[]{false, true}) {
            InputArbiter arb = new InputArbiter();
            InputArbiter.Lease alpha;
            InputArbiter.Lease zebra;
            if (zebraFirst) {
                zebra = arb.claim("zebra", 0, true, InputArbiter.Resource.MOVE);
                alpha = arb.claim("alpha", 100, false, InputArbiter.Resource.MOVE);
            } else {
                alpha = arb.claim("alpha", 100, false, InputArbiter.Resource.MOVE);
                long before = alpha.epoch();
                assertEquals(InputArbiter.Submit.OK, arb.submit("alpha", before, down(Input.MOVE_FORWARD, Input.SPRINT)));
                assertDown(arb.seal(), Input.MOVE_FORWARD, Input.SPRINT);
                zebra = arb.claim("zebra", 0, true, InputArbiter.Resource.MOVE);
                assertEquals(InputArbiter.Submit.STALE, arb.submit("alpha", before, down(Input.MOVE_FORWARD)));
                assertFalse(arb.applied().get(Input.MOVE_FORWARD));
                assertFalse(arb.applied().get(Input.SPRINT));
            }
            assertTrue(zebra.holds(InputArbiter.Resource.MOVE));
            assertFalse(alpha.alive());
            assertEquals(InputArbiter.Submit.NO_LEASE, arb.submit("alpha", alpha.epoch(), down(Input.MOVE_FORWARD)));
            assertEquals(InputArbiter.Submit.OK, arb.submit("zebra", zebra.epoch(), down()));
            assertDown(arb.seal());
        }
    }

    @Test
    public void aMissingKeyIsUpOnTheNextFrame() {
        InputArbiter arb = new InputArbiter();
        InputArbiter.Lease owner = arb.claim("walker", 1, false, InputArbiter.Resource.MOVE);
        assertEquals(InputArbiter.Submit.OK, arb.submit("walker", owner.epoch(), down(Input.MOVE_FORWARD, Input.SPRINT)));
        assertDown(arb.seal(), Input.MOVE_FORWARD, Input.SPRINT);
        assertEquals(InputArbiter.Submit.OK, arb.submit("walker", owner.epoch(), down(Input.MOVE_FORWARD)));
        assertDown(arb.seal(), Input.MOVE_FORWARD);
        arb.seal();
        assertDown(arb.applied());
    }

    @Test
    public void fallChordListsEveryKeyAndTheFlagDefaultsOff() throws Exception {
        Map<Input, Boolean> chord = FallInput.chord(true, true, false, false, false, false, true);
        assertEquals(Input.values().length, chord.size());
        for (Input input : Input.values()) {
            assertTrue(chord.containsKey(input));
        }
        assertFalse(chord.get(Input.SPRINT));
        assertFalse(chord.get(Input.CLICK_LEFT));
        assertTrue(chord.get(Input.MOVE_FORWARD));
        assertTrue(chord.get(Input.CLICK_RIGHT));

        String settings = read("src/api/java/baritone/api/Settings.java");
        assertTrue(settings.contains("inputArbitration = new Setting<>(false)"));
        String fall = read("src/main/java/baritone/pathing/kinematic/FallController.java");
        int flag = fall.indexOf("!Baritone.settings().inputArbitration.value");
        int arbiter = fall.indexOf("getInputArbiter");
        assertTrue(flag >= 0 && arbiter > flag);
        assertTrue(fall.contains("in.clearAllKeys()"));
        assertTrue(fall.contains("in.setInputForceState(Input.MOVE_FORWARD, forward)"));
        assertTrue(fall.contains("endLease(baritone, true)"));
        int legacyReturn = fall.indexOf("return r;");
        int tryStart = fall.indexOf("try {");
        assertTrue(legacyReturn > flag && legacyReturn < tryStart);
    }

    private static Map<Input, Boolean> down(Input... keys) {
        Map<Input, Boolean> frame = new EnumMap<>(Input.class);
        for (Input key : keys) {
            frame.put(key, Boolean.TRUE);
        }
        return frame;
    }

    private static void assertDown(Map<Input, Boolean> frame, Input... down) {
        Set<Input> want = EnumSet.noneOf(Input.class);
        Collections.addAll(want, down);
        for (Input input : Input.values()) {
            assertEquals(input.name(), want.contains(input), Boolean.TRUE.equals(frame.get(input)));
        }
    }

    private static String read(String rel) throws Exception {
        Path path = Paths.get(rel);
        if (!Files.exists(path)) {
            path = Paths.get("..").resolve(rel);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
