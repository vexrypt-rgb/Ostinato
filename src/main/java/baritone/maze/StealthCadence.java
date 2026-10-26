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

package baritone.maze;

/**
 * How many game ticks to wait before a maze action. The wait is a pure function of the seed and the action index,
 * so it does not flicker and two runs of the same room wait the same way. Ticks are 20 per second.
 */
public final class StealthCadence {

    public final int minDelay;
    public final int maxDelay;
    public final int jitter;

    private StealthCadence(int minDelay, int maxDelay, int jitter) {
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
    }

    /** Clamps a bad setting instead of throwing on the game thread. */
    public static StealthCadence from(int minDelay, int maxDelay, int jitter) {
        if (minDelay < 0) {
            minDelay = 0;
        }
        if (maxDelay < minDelay) {
            maxDelay = minDelay;
        }
        if (jitter < 0) {
            jitter = 0;
        }
        return new StealthCadence(minDelay, maxDelay, jitter);
    }

    public int delayTicks(long seed, int actionIndex) {
        int span = maxDelay - minDelay;
        int base = minDelay;
        if (span > 0) {
            base += (int) Long.remainderUnsigned(MazeSeed.mix(seed, actionIndex, 1), span + 1L);
        }
        int signed = 0;
        if (jitter > 0) {
            signed = (int) Long.remainderUnsigned(MazeSeed.mix(seed, actionIndex, 3), jitter * 2L + 1) - jitter;
        }
        int delay = base + signed;
        return Math.max(0, delay);
    }
}
