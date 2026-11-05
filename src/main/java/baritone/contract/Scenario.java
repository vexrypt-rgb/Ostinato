/*
 * Ostinato: the shared setup for one comparison. Not part of upstream Baritone.
 */
package baritone.contract;

/** Start, goal, and budgets. Equivalent rows must use the same instance. */
public record Scenario(String name, WorldAccess world, String initialRequirements, PlayerState start,
                       double goalX, double goalY, double goalZ, double radius, int tickBudget, int nodeBudget) {
}
