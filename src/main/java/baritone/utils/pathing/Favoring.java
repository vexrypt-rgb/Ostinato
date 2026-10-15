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

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import java.util.ArrayList;
import java.util.List;

public final class Favoring {

    private final Long2DoubleOpenHashMap favorings;
    /** Mobs, asked per step. Spawners and the backtrack discount stay in {@link #favorings}. */
    private List<Avoidance> live = List.of();
    /** Spawners, bastion avoids, or live mobs priced this search. Backtrack favoring does not set it. */
    private boolean avoidance;

    public Favoring(IPlayerContext ctx, IPath previous, CalculationContext context) {
        this(previous, context);
        List<Avoidance> query = new ArrayList<>();
        boolean any = false;
        for (Avoidance avoid : Avoidance.create(ctx)) {
            any = true;
            if (avoid.queried()) {
                query.add(avoid);
            } else {
                avoid.applySpherical(favorings);
            }
        }
        if (context.getBaritone() instanceof baritone.Baritone b) {
            for (Avoidance avoid : b.getBastionProcess().avoid()) {
                any = true;
                avoid.applySpherical(favorings);
            }
        }
        avoidance = any;
        live = query.isEmpty() ? List.of() : query;
        Helper.HELPER.logDebug("Favoring size: " + favorings.size() + " mobs: " + live.size());
    }

    public Favoring(IPath previous, CalculationContext context) { // create one just from previous path, no mob avoidances
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        double coeff = context.backtrackCostFavoringCoefficient;
        if (coeff != 1D && previous != null) {
            previous.positions().forEach(pos -> favorings.put(BetterBlockPos.longHash(pos), coeff));
        }
    }

    public boolean isEmpty() {
        return favorings.isEmpty() && live.isEmpty();
    }

    /** True when mobs or spawners changed the costs. A stored route would not match this search. */
    public boolean hasAvoidance() {
        return avoidance;
    }

    public double calculate(int x, int y, int z) {
        double cost = favorings.get(BetterBlockPos.longHash(x, y, z));
        for (int i = 0, n = live.size(); i < n; i++) {
            double mob = live.get(i).coefficient(x, y, z);
            if (mob != 1.0D) {
                cost *= mob;
            }
        }
        return cost;
    }
}
