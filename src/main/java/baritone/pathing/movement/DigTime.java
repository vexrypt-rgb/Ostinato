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

package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;

/**
 * Ostinato: real block break times, as vanilla 26.3 and our BlockBreakHelper do them.
 * <ul>
 *     <li>BlockBehaviour.getDestroyProgress: per tick progress = destroySpeed / hardness / (correct tool ? 30 : 100),
 *     where Player.getDestroySpeed = tool speed (+ MINING_EFFICIENCY, Efficiency n = n*n + 1, only if speed > 1)
 *     * (1 + 0.2 * (haste or conduit amplifier + 1)) * 0.3^(fatigue amplifier + 1) * BLOCK_BREAK_SPEED
 *     * SUBMERGED_MINING_SPEED (0.2, Aqua Affinity 1) if eyes in water, / 5 if not on ground.</li>
 *     <li>MultiPlayerGameMode: progress >= 1 on the start tick is an instant break, one block a tick, no delay. Else the
 *     start tick adds nothing, then whole ticks until the float sum reaches 1 (ceil), then destroyDelay = 5. Our
 *     BlockBreakHelper replaces that with blockBreakSpeed - 1 skipped ticks; with the start tick that's a gap of
 *     blockBreakSpeed (default 6, same as vanilla) per non-instant block.</li>
 * </ul>
 * Baritone used 1/progress (fractional, no start tick, no delay) plus a flat 2 tick penalty.
 */
public final class DigTime {

    private DigTime() {}

    /** Ticks for one block in a row of them, from the per tick progress. */
    public static double ticks(double progressPerTick, int breakGap) {
        if (progressPerTick <= 0 || Double.isNaN(progressPerTick)) {
            return ActionCosts.COST_INF;
        }
        if (Double.isInfinite(progressPerTick) || progressPerTick >= 1) {
            return 1;
        }
        return Math.ceil(1 / progressPerTick - 1e-9) + Math.max(1, breakGap);
    }

    /** Vanilla per tick progress. */
    public static double progress(double toolSpeed, int efficiency, double hardness, boolean correctTool) {
        if (hardness < 0) {
            return 0;
        }
        if (hardness == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double speed = toolSpeed;
        if (speed > 1 && efficiency > 0) {
            speed += efficiency * efficiency + 1;
        }
        return speed / hardness / (correctTool ? 30 : 100);
    }

    /** Haste or Conduit Power (the higher amplifier counts), -1 for none. */
    public static double haste(int amplifier) {
        return amplifier < 0 ? 1 : 1 + (amplifier + 1) * 0.2;
    }

    /** Mining Fatigue, -1 for none. Baritone had 0.0027 and 0.00081 for III and IV, ten times too slow. */
    public static double fatigue(int amplifier) {
        return amplifier < 0 ? 1 : Math.pow(0.3, amplifier + 1);
    }

    /** Eyes in water without Aqua Affinity (SUBMERGED_MINING_SPEED 0.2), and not on the ground (/5). */
    public static double situation(boolean eyesInWater, double submergedSpeed, boolean onGround) {
        double f = eyesInWater ? submergedSpeed : 1;
        return onGround ? f : f / 5;
    }
}
