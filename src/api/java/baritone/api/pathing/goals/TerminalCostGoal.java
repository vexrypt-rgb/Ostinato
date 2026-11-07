/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.api.pathing.goals;

/**
 * A goal whose cells are not all worth the same: reaching a cell is only part of the job, and what is left to do from
 * there (aim, set up an interaction, stand somewhere riskier) costs a different amount per cell.
 * <p>
 * The search charges {@link #terminalCost} once, when it first reaches a goal cell, and only stops at a goal cell after
 * that charge has been paid, so it ends where travel plus terminal cost is lowest rather than at the first goal cell it
 * pops. A goal whose terminal costs are all 0 behaves exactly like a plain {@link Goal}.
 */
public interface TerminalCostGoal extends Goal {

    /**
     * Ticks still to spend after standing at this goal cell, in the same units as movement costs. Must be 0 or more
     * and finite for every cell where {@link #isInGoal} is true.
     */
    double terminalCost(int x, int y, int z);

    /**
     * Ostinato Cadenza v2: the same, knowing where the path arrives from: {@code (fromX, fromY, fromZ)} is the node
     * before this cell on the path (the cell itself when the path is empty), so a goal can charge the turn the arrival
     * heading leaves to make. The search calls this one; the default ignores the heading.
     */
    default double terminalCost(int x, int y, int z, int fromX, int fromY, int fromZ) {
        return terminalCost(x, y, z);
    }
}
