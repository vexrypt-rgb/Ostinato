/*
 * Ostinato: one tick of an existing physics model. Not part of upstream Baritone.
 */
package baritone.contract;

/** Advances a body by one control tick. Implementations call an existing sim; they do not contain a second model. */
public interface PhysicsTransition {
    String semantics();

    PlayerState state();

    PlayerState step(ControlTick control);
}
