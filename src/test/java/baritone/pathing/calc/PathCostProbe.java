/*
 * Ostinato: review fix. Test-only access to a package-private path's summed movement cost. Not upstream Baritone.
 */
package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;

public final class PathCostProbe {
    private PathCostProbe() {
    }

    /** The A* cost in ticks of a path this package built, or NaN for any other path. */
    public static double totalCost(IPath path) {
        return path instanceof Path p ? p.totalCost() : Double.NaN;
    }
}
