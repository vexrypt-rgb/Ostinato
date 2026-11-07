/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.kinematic;

import baritone.api.utils.input.Input;
import baritone.utils.InputArbiter;

import java.util.EnumMap;
import java.util.Map;

// Ostinato: the fall controller's chord. Sprint stays up. Used only when inputArbitration is on.

/**
 * The one live-path owner on the input arbiter. The chord lists every key, including the ones that stay up.
 */
public final class FallInput {

    public static final String OWNER = "fall";
    public static final int PRIORITY = 10;
    public static final InputArbiter.Resource[] LEASE = {
            InputArbiter.Resource.MOVE,
            InputArbiter.Resource.JUMP,
            InputArbiter.Resource.SNEAK,
            InputArbiter.Resource.CLICK
    };

    private FallInput() {}

    /** All nine inputs. Off keys are false, not omitted. Fall never sprints. */
    public static Map<Input, Boolean> chord(boolean forward, boolean back, boolean left, boolean right,
                                             boolean sneak, boolean jump, boolean clickRight) {
        EnumMap<Input, Boolean> frame = new EnumMap<>(Input.class);
        for (Input input : Input.values()) {
            frame.put(input, Boolean.FALSE);
        }
        frame.put(Input.MOVE_FORWARD, forward);
        frame.put(Input.MOVE_BACK, back);
        frame.put(Input.MOVE_LEFT, left);
        frame.put(Input.MOVE_RIGHT, right);
        frame.put(Input.SNEAK, sneak);
        frame.put(Input.JUMP, jump);
        frame.put(Input.CLICK_RIGHT, clickRight);
        return frame;
    }

    /** Submit and seal. Null when the epoch is stale or the lease is gone. */
    public static Map<Input, Boolean> submit(InputArbiter arb, InputArbiter.Lease lease, Map<Input, Boolean> chord) {
        if (arb == null || lease == null) {
            return null;
        }
        if (arb.submit(lease.id(), lease.epoch(), chord) != InputArbiter.Submit.OK) {
            return null;
        }
        return arb.seal();
    }

    /**
     * Confirm release, or fail the lease. Null when there is nothing to end.
     * Does not seal, so another owner's queued frame stays queued.
     */
    public static Map<Input, Boolean> end(InputArbiter arb, InputArbiter.Lease lease, boolean failed) {
        if (arb == null || lease == null || lease.end() != null) {
            return null;
        }
        if (failed) {
            arb.fail(lease.id());
        } else {
            arb.confirmRelease(lease.id());
        }
        return arb.applied();
    }
}
