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
package baritone.pathing.calc;

import baritone.utils.pathing.LazyCheck;

/**
 * Ostinato: an offer of a cheaper way into a node over a move not checked yet. The node keeps its checked cost and
 * parent until the offer is popped and checked, so a failed check falls back to them.
 */
public final class PendingOffer {

    /** Cost to the node through this offer, as offered. */
    double cost;
    /** The move's unscaled offered cost, and the scale the search applied to it. */
    double raw, mult;
    PathNode prev;
    LazyCheck check;
    /** The next costlier offer into the same node. */
    PendingOffer next;

    PendingOffer(double cost, double raw, double mult, PathNode prev, LazyCheck check) {
        set(cost, raw, mult, prev, check);
    }

    /** Ostinato: refill a recycled offer. piCheapPathing only; the stock search always constructs a new one. */
    void set(double cost, double raw, double mult, PathNode prev, LazyCheck check) {
        this.cost = cost;
        this.raw = raw;
        this.mult = mult;
        this.prev = prev;
        this.check = check;
        this.next = null;
    }
}
