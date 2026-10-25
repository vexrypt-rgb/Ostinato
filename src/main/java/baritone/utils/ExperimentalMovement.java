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
// Ostinato: fastMode ORs into the parkour, diagonal, bias and placement helpers. TAS and kinematic stay on this preset.

/**
 * experimentalMovement is a preset, not a setting rewrite: the real settings never change, so switching it off hands the
 * user their own choices back. Everything that has an opinion about one of these reads it through here.
 */
public final class ExperimentalMovement {

    /** Vanilla safe fall distance, and a sanity bound on a hurting fall (23 blocks is exactly 20 hp of it). */
    public static final int SAFE_FALL = 3;
    public static final int MAX_HURT_FALL = 23;

    private ExperimentalMovement() {}

    public static boolean on() {
        return Baritone.settings().experimentalMovement.value;
    }

    public static boolean allowParkour() {
        // Ostinato: fastMode opens parkour without writing the setting. Off, this is the old answer.
        return on() || FastMode.on() || Baritone.settings().allowParkour.value;
    }

    public static boolean allowParkourAscend() {
        return on() || FastMode.on() || Baritone.settings().allowParkourAscend.value;
    }

    public static boolean allowNeos() {
        return on() || FastMode.on() || Baritone.settings().allowNeos.value;
    }

    public static boolean allowClimbJumps() {
        return on() || FastMode.on() || Baritone.settings().allowClimbJumps.value;
    }

    public static boolean allowTasJumps() {
        return on() || Baritone.settings().allowTasJumps.value;
    }

    public static boolean allowMomentumJumps() {
        return on() || FastMode.on() || Baritone.settings().allowMomentumJumps.value;
    }

    public static boolean allowDiagonalAscend() {
        return on() || FastMode.on() || Baritone.settings().allowDiagonalAscend.value;
    }

    public static boolean allowDiagonalDescend() {
        return on() || FastMode.on() || Baritone.settings().allowDiagonalDescend.value;
    }

    /** The kinematic controller is what flies the jumps, so the preset implies it. */
    public static boolean kinematicTravel() {
        return on() || Baritone.settings().kinematicTravel.value;
    }

    /** The lower of the two, so somebody who already set a cheaper penalty keeps it. fastMode uses the same cap. */
    public static double blockPlacementPenalty() {
        double penalty = Baritone.settings().blockPlacementPenalty.value;
        // Ostinato: off (neither preset), this is blockPlacementPenalty unchanged.
        return on() || FastMode.on() ? Math.min(penalty, Baritone.settings().experimentalBlockPlacementPenalty.value) : penalty;
    }

    public static double jumpBias() {
        // Ostinato: off, jumps are not discounted.
        return on() || FastMode.on() ? Baritone.settings().experimentalJumpBias.value : 1;
    }

    /** LivingEntity.calculateFallDamage before armor and feather falling, which only make it smaller. */
    public static int fallDamage(int blocksFallen) {
        return Math.max(0, blocksFallen - SAFE_FALL);
    }

    public static boolean canAffordFall(double health, double damage, double minHealth) {
        return health - damage >= minHealth;
    }
}
