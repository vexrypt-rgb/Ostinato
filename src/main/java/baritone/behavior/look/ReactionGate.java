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

package baritone.behavior.look;

import java.util.Random;

/**
 * Ostinato: the surprise reaction delay (humanReactionDelay). When a new, unexpected look target appears (a mob
 * showing up, being hit, a new threat) the camera holds still for a sampled reaction time before the hand moves.
 * The hold only applies on ticks whose look is a reactive one (combat aim); any other look (path following,
 * headSteering, mining, parkour, planned turns) cancels it at once, so planned movement is never delayed.
 */
public final class ReactionGate {

    private int holdTicks;

    /**
     * A surprise: starts a hold of a reaction time sampled from {@code model} at the given energy, in 50 ms ticks.
     * Returns the hold length (0 when disabled).
     */
    public int surprise(boolean enabled, ReactionModel model, ReactionModel.Energy energy, double nowSec, double sessionSec, Random rng) {
        if (!enabled) {
            holdTicks = 0;
            return 0;
        }
        double ms = model.sample(energy, nowSec, sessionSec, rng);
        holdTicks = (int) Math.round(ms / 50.0);
        return holdTicks;
    }

    /**
     * One tick of a look. Returns true when the camera must stay where it is this tick. A look that isn't
     * reactive ends any hold immediately and is never delayed.
     */
    public boolean hold(boolean reactiveLook) {
        if (!reactiveLook) {
            holdTicks = 0;
            return false;
        }
        if (holdTicks > 0) {
            holdTicks--;
            return true;
        }
        return false;
    }

    public boolean holding() {
        return holdTicks > 0;
    }

    public void cancel() {
        holdTicks = 0;
    }
}
