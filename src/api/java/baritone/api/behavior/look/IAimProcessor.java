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

package baritone.api.behavior.look;

import baritone.api.utils.Rotation;

/**
 * @author Brady
 */
public interface IAimProcessor {

    /**
     * Returns the actual rotation that will be used when the desired rotation is requested. The returned rotation
     * always reflects what would happen in the upcoming tick. In other words, it is a pure function, and no internal
     * state changes. If simulation of the rotation states beyond the next tick is required, then a
     * {@link IAimProcessor#fork fork} should be created.
     *
     * @param desired The desired rotation to set
     * @return The actual rotation
     */
    Rotation peekRotation(Rotation desired);

    /**
     * Ostinato: run100. Where the look settles once it gets to {@code desired}: aim noise and mouse steps, but not the
     * per-tick turn cap that {@link #peekRotation} applies. Reach and placement checks ask "would the ray hit if I
     * looked there", and with the 32 degree cap every block more than 32 degrees off the view read as unreachable,
     * so nothing ever turned to it (a log at 1.1 blocks, a crafting table under the feet).
     *
     * @param desired The desired rotation
     * @return The rotation the look ends on
     */
    default Rotation peekAim(Rotation desired) {
        return peekRotation(desired);
    }

    /**
     * Returns a copy of this {@link IAimProcessor} which has its own internal state and is manually tickable.
     *
     * @return The forked processor
     * @see ITickableAimProcessor
     */
    ITickableAimProcessor fork();
}
