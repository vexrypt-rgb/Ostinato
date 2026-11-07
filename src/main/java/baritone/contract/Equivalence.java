/*
 * Ostinato: whether a comparison row shares the reference scenario. Not part of upstream Baritone.
 */
package baritone.contract;

/** Equivalent rows share world, start, physics semantics, and budgets. */
public enum Equivalence {
    EQUIVALENT,
    NOT_EQUIVALENT
}
