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

package baritone.api.behavior;

import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.utils.Rotation;

/**
 * @author Brady
 * @since 9/23/2018
 */
public interface ILookBehavior extends IBehavior {

    /**
     * Ostinato: mark the next requested rotation as a hand's mouse move rather than a silent server-only turn.
     * The client's {@code LookBehavior} implements it; a headless bot has no hand and needs no override.
     */
    default void human() {}

    /**
     * Ostinato: the yaw velocity of the camera, in degrees per tick, used by the kinetic jump controller.
     * A headless bot's aim is set directly, so it has none.
     */
    default float yawVelocity() {
        return 0f;
    }

    /**
     * Ostinato: for this tick only, let the look turn up to {@code deg} degrees instead of the client's usual cap. A
     * headless look applies each target outright, so it has no cap and ignores this.
     */
    default void turnCap(float deg) {}

    /**
     * Updates the current {@link ILookBehavior} target to target the specified rotations on the next tick. If any sort
     * of block interaction is required, {@code blockInteract} should be {@code true}. It is not guaranteed that the
     * rotations set by the caller will be the exact rotations expressed by the client (This is due to settings like
     * {@link Settings#randomLooking}). If the rotations produced by this behavior are required, then the
     * {@link #getAimProcessor() aim processor} should be used.
     *
     * @param rotation      The target rotations
     * @param blockInteract Whether the target rotations are needed for a block interaction
     */
    void updateTarget(Rotation rotation, boolean blockInteract);

    /**
     * The aim processor instance for this {@link ILookBehavior}, which is responsible for applying additional,
     * deterministic transformations to the target rotation set by {@link #updateTarget}.
     *
     * @return The aim processor
     * @see IAimProcessor#fork
     */
    IAimProcessor getAimProcessor();
}
