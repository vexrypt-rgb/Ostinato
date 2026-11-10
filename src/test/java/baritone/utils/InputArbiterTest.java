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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    /** Review fix: an owner asked to cancel that never acks is still the owner, however long it takes. */
    @Test
    public void cancelWithoutAckIsNeverTreatedAsStopped() {
        InputArbiter arb = new InputArbiter();
        InputArbiter.Lease owner = arb.claim("fall", 10, false, InputArbiter.Resource.MOVE, InputArbiter.Resource.JUMP);
        long epoch = owner.epoch();
        arb.requestCancel("fall");
        for (int tick = 0; tick < 1200; tick++) {
            if (tick < 600) {
                assertEquals(InputArbiter.Submit.OK, arb.submit("fall", epoch, down(Input.MOVE_FORWARD, Input.JUMP)));
                assertDown(arb.seal(), Input.MOVE_FORWARD, Input.JUMP);
            } else {
                assertDown(arb.seal());
            }
            assertTrue(owner.alive());
            assertTrue(owner.holds(InputArbiter.Resource.MOVE));
            assertNull(owner.end());
            assertEquals(epoch, owner.epoch());
        }
        InputArbiter.Lease lower = arb.claim("walker", 1, false, InputArbiter.Resource.MOVE);
        assertFalse(lower.alive());
        assertTrue(owner.holds(InputArbiter.Resource.MOVE));
        arb.confirmRelease("fall");
        assertEquals(InputArbiter.End.RELEASED, owner.end());
        assertTrue(arb.claim("walker", 1, false, InputArbiter.Resource.MOVE).alive());
    }

    /** Review fix: random claim, submit, cancel, release, fail and releaseAll. No resource ever has two living holders. */
    @Test
    public void neverTwoOwnersOfOneResourceInAnyTick() {
        String[] ids = {"a", "b", "c", "d", "e"};
        InputArbiter.Resource[] all = InputArbiter.Resource.values();
        Input[] keys = Input.values();
        for (long seed = 1; seed <= 200; seed++) {
            Random rnd = new Random(seed);
            InputArbiter arb = new InputArbiter();
            Map<String, InputArbiter.Lease> leases = new HashMap<>();
            for (int step = 0; step < 400; step++) {
                String id = ids[rnd.nextInt(ids.length)];
                int op = rnd.nextInt(10);
                if (op < 4) {
                    List<InputArbiter.Resource> want = new ArrayList<>();
                    for (InputArbiter.Resource r : all) {
                        if (rnd.nextBoolean()) {
                            want.add(r);
                        }
                    }
                    leases.put(id, arb.claim(id, rnd.nextInt(3), rnd.nextInt(8) == 0, want.toArray(new InputArbiter.Resource[0])));
                } else if (op < 7) {
                    InputArbiter.Lease lease = leases.get(id);
                    if (lease != null) {
                        arb.submit(id, lease.epoch(), down(keys[rnd.nextInt(keys.length)], Input.MOVE_FORWARD, Input.JUMP));
                    }
                } else if (op == 7) {
                    arb.requestCancel(id);
                } else if (op == 8) {
                    if (rnd.nextBoolean()) {
                        arb.confirmRelease(id);
                    } else {
                        arb.fail(id);
                    }
                } else if (rnd.nextInt(20) == 0) {
                    arb.releaseAll();
                }
                Map<InputArbiter.Resource, String> holder = new EnumMap<>(InputArbiter.Resource.class);
                for (InputArbiter.Lease lease : leases.values()) {
                    for (InputArbiter.Resource r : all) {
                        if (!lease.holds(r)) {
                            continue;
                        }
                        assertTrue("dead lease keeps " + r + " seed " + seed, lease.alive());
                        assertNull("two owners of " + r + " seed " + seed + " step " + step, holder.put(r, lease.id()));
                    }
                }
                Map<Input, Boolean> frame = arb.seal();
                for (Input key : keys) {
                    if (!Boolean.TRUE.equals(frame.get(key))) {
                        continue;
                    }
                    boolean owned = false;
                    for (InputArbiter.Resource r : holder.keySet()) {
                        owned |= keysOf(r).contains(key);
                    }
                    assertTrue("unowned key down " + key + " seed " + seed + " step " + step, owned);
                }
            }
        }
    }

    /** Review fix: disconnect or world change ends every lease and stales queued frames. */
    @Test
    public void releaseAllEndsEveryLeaseAndStalesQueuedFrames() {
        InputArbiter arb = new InputArbiter();
        InputArbiter.Lease fall = arb.claim("fall", 10, false, InputArbiter.Resource.MOVE, InputArbiter.Resource.SNEAK);
        InputArbiter.Lease hop = arb.claim("hop", 1, false, InputArbiter.Resource.JUMP);
        long fallEpoch = fall.epoch();
        assertEquals(InputArbiter.Submit.OK, arb.submit("fall", fallEpoch, down(Input.MOVE_FORWARD)));
        assertEquals(InputArbiter.Submit.OK, arb.submit("hop", hop.epoch(), down(Input.JUMP)));
        assertDown(arb.seal(), Input.MOVE_FORWARD, Input.JUMP);
        assertEquals(InputArbiter.Submit.OK, arb.submit("fall", fallEpoch, down(Input.MOVE_FORWARD)));

        arb.releaseAll();
        assertFalse(fall.alive());
        assertFalse(hop.alive());
        assertEquals(InputArbiter.End.RELEASED, fall.end());
        assertEquals(InputArbiter.End.RELEASED, hop.end());
        assertDown(arb.applied());
        assertDown(arb.seal());
        assertEquals(InputArbiter.Submit.STALE, arb.submit("fall", fallEpoch, down(Input.MOVE_FORWARD)));
        arb.releaseAll();
        assertTrue(arb.claim("fall", 10, false, InputArbiter.Resource.MOVE).alive());
    }

    /** Review fix: executor cancel, segment cancel and world change give leases back. Source scan, like the flag test. */
    @Test
    public void executorCancelSegmentCancelAndWorldChangeReleaseLeases() throws Exception {
        String executor = read("src/main/java/baritone/pathing/path/PathExecutor.java").replace("\r\n", "\n");
        int cancel = executor.indexOf("private void cancel() {");
        assertTrue(cancel >= 0);
        int release = executor.indexOf("releaseInputLeases();", cancel);
        assertTrue(release > cancel && release < executor.indexOf("\n    }", cancel));
        assertTrue(executor.contains("fall.release(behavior.baritone);"));
        String behavior = read("src/main/java/baritone/behavior/PathingBehavior.java").replace("\r\n", "\n");
        int segment = behavior.indexOf("public void secretInternalSegmentCancel()");
        int segRelease = behavior.indexOf("current.releaseInputLeases();", segment);
        assertTrue(segment >= 0 && segRelease > segment && segRelease < behavior.indexOf("current = null;", segment));
        int world = behavior.indexOf("public void onWorldEvent(WorldEvent event)");
        int worldRelease = behavior.indexOf("getInputArbiter().releaseAll()", world);
        assertTrue(world >= 0 && worldRelease > world && worldRelease < behavior.indexOf("\n    }", world));
    }

    /** The key groups {@link InputArbiter.Resource} documents. */
    private static Set<Input> keysOf(InputArbiter.Resource r) {
        switch (r) {
            case MOVE:
                return EnumSet.of(Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SPRINT);
            case JUMP:
                return EnumSet.of(Input.JUMP);
            case SNEAK:
                return EnumSet.of(Input.SNEAK);
            default:
                return EnumSet.of(Input.CLICK_LEFT, Input.CLICK_RIGHT);
        }
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
