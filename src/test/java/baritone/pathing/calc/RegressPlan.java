/*
 * Ostinato: regression suite access to the search, from inside its package. New file, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;

import java.util.List;
import java.util.Optional;

/**
 * Ostinato: runs a search with its caches claimed, as {@link AbstractNodeCostSearch#calculate} does, and returns the
 * raw path. Not post-processed: assembling Movement objects needs an IBaritone with a player, which a headless run
 * has none of (and calculate() reports exceptions through chat, which needs the client).
 */
public final class RegressPlan {

    private RegressPlan() {}

    public static Optional<IPath> plan(AbstractNodeCostSearch search, CalculationContext context, long timeoutMs) {
        context.claimSearchCaches();
        try {
            return search.calculate0(timeoutMs, timeoutMs);
        } finally {
            context.releaseSearchCaches();
        }
    }

    /** A* cost of the path's end node, in ticks. */
    public static double cost(IPath p) {
        return p instanceof Path path ? path.totalCost() : Double.NaN;
    }

    public static List<BetterBlockPos> positions(IPath p) {
        return p.positions();
    }
}
