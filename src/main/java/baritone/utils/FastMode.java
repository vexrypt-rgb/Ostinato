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

package baritone.utils;

import baritone.Baritone;

// Ostinato: fastMode is a preset. It never writes the other settings, so turning it off hands those choices back.
// While it is off every method below is exactly the setting it wraps.

/**
 * The speedrunner switch. Parkour, diagonals, jump pricing and the fall floor go through
 * {@link ExperimentalMovement}; the flags that preset does not own are read here.
 */
public final class FastMode {

    private FastMode() {}

    public static boolean on() {
        return Baritone.settings().fastMode.value;
    }

    public static boolean allowParkourPlace() {
        return on() || Baritone.settings().allowParkourPlace.value;
    }

    public static boolean allowLadderClutch() {
        return on() || Baritone.settings().allowLadderClutch.value;
    }

    public static boolean sprintJump() {
        return on() || Baritone.settings().sprintJump.value;
    }

    public static boolean swimInWater() {
        return on() || Baritone.settings().swimInWater.value;
    }

    public static boolean allowBoats() {
        return on() || Baritone.settings().allowBoats.value;
    }

    /** Elytra takeoff from the ground. The elytra process still requires its own terms and gear. */
    public static boolean elytraGroundTakeoff() {
        return on() || Baritone.settings().elytraAutoJump.value;
    }
}
