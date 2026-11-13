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

package baritone.swarm.lease;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ostinato: Phase 2c. What a leased mine does with the vein box it holds. The box came from the lease, which the bot
 * already checked in its own world (a vein picked from another bot's report is verified before the command runs), so
 * the mine may head for it even where its own seen-only scan knows no ore yet. Only ore inside the box is a target;
 * once this bot's world shows the box without the leased ore, the vein is done and the mine ends (it does not go on
 * to dig ore it never reserved).
 */
public final class LeasedVein {

    /** Bigger boxes are not walked block by block; the mine heads for the centre instead. */
    public static final int MAX_SCAN = 32_768;

    /** What the bot's own world shows at a position. */
    public interface Blocks {
        int MATCH = 1;
        int OTHER = 0;
        int UNLOADED = -1;

        int at(int x, int y, int z);
    }

    public enum State {
        /** The leased ore is still in the box: these positions are the targets. */
        ORE,
        /** Part of the box is not loaded here (or it is too big to walk): head for its centre. */
        UNKNOWN,
        /** The whole box is loaded and holds none of the leased ore: the vein is mined out. */
        MINED_OUT
    }

    public static final class Scan {
        public final State state;
        public final List<int[]> ore;

        Scan(State state, List<int[]> ore) {
            this.state = state;
            this.ore = ore;
        }
    }

    private LeasedVein() {}

    public static Scan scan(ReservationKey k, Blocks b) {
        long volume = (long) (k.x2 - k.x1 + 1) * (k.y2 - k.y1 + 1) * (k.z2 - k.z1 + 1);
        if (volume > MAX_SCAN) {
            return new Scan(State.UNKNOWN, Collections.emptyList());
        }
        List<int[]> ore = new ArrayList<>();
        boolean unloaded = false;
        for (int x = k.x1; x <= k.x2; x++) {
            for (int y = k.y1; y <= k.y2; y++) {
                for (int z = k.z1; z <= k.z2; z++) {
                    int s = b.at(x, y, z);
                    if (s == Blocks.MATCH) {
                        ore.add(new int[]{x, y, z});
                    } else if (s == Blocks.UNLOADED) {
                        unloaded = true;
                    }
                }
            }
        }
        if (!ore.isEmpty()) {
            return new Scan(State.ORE, ore);
        }
        return new Scan(unloaded ? State.UNKNOWN : State.MINED_OUT, ore);
    }

    /**
     * Whether a known mine target stays a goal while the vein {@code k} is held. With no vein everything stays.
     * With one, ore outside the box is dropped (the fence would deny breaking it); other positions, such as
     * dropped items to pick up, stay. The key comes from {@code LeaseFence.veinTarget()}, which only hands out a
     * vein in the bot's own dimension.
     */
    public static boolean keep(ReservationKey k, int x, int y, int z, boolean ore) {
        return k == null || !ore || k.contains(k.dim, x, y, z);
    }

    /** The centre block of the box. */
    public static int[] centre(ReservationKey k) {
        return new int[]{Math.floorDiv(k.x1 + k.x2, 2), Math.floorDiv(k.y1 + k.y2, 2), Math.floorDiv(k.z1 + k.z2, 2)};
    }
}
