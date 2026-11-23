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

package baritone.process;

/**
 * Ostinato: the outcome of mine requests. A request succeeds only if it broke at least one target block; one that
 * ended itself having broken none failed (nothing found, nothing reachable); one whose control was taken away first
 * was cancelled. The release gate (17 Nov) found a mine with nothing to mine reported "succeeded (verified)" because
 * the swarm executor took a process going idle as success. The rule itself is {@link RequestOutcome}'s.
 */
final class MineOutcome extends RequestOutcome {

    int mined() {
        return done();
    }

    /** One target block of this request was broken. */
    void minedOne() {
        didOne();
    }
}
