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
package baritone.utils.pathing;

/**
 * Ostinato: a move whose cost is offered before its expensive check (a simulated jump). The pathfinder runs the check
 * only when it pops the destination node, so jumps the search never reaches are never simulated.
 */
public interface LazyCheck {

    /**
     * Runs the deferred check.
     *
     * @return the move's checked cost, at least the offered one, or COST_INF when the move fails
     */
    double verify();
}
