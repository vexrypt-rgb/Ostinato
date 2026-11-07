/*
 * Ostinato: names the world a comparison is allowed to read. Not part of upstream Baritone.
 */
package baritone.contract;

/** The collision source for one physics semantics. Two semantics do not share an implementation. */
public interface WorldAccess {
    String semantics();
}
