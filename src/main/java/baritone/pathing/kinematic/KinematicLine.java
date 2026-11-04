/*
 * Ostinato: new file (kinematic MovementLine). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.pathing.movement.movements.MovementLine;

import java.util.concurrent.atomic.LongAdder;

/**
 * Ostinato: may the kinematic driver steer this 2-by-1 knight move? Behind {@code kinematicLine} (off by default):
 * only a flat line whose whole rectangle was plain ground at its last cost check. Everything else stays with
 * Baritone's own {@link MovementLine#updateState}, exactly as before.
 * <p>
 * The decision is counted once per line, and again whenever it changes (a line accepted earlier and refused now
 * is a downgrade). These are selection counts: what the driver really steered is measured by the caller per tick.
 */
public final class KinematicLine {
    /** PENDING: not cost-checked yet (beyond the executor's lookahead); the stretch ends before it for now. */
    public static final int ACCEPTED = 0, OFF = 1, NOT_FLAT = 2, GROUND = 3, PENDING = 4;
    private static final LongAdder[] DECISIONS = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};
    private static final LongAdder DOWNGRADES = new LongAdder();

    private KinematicLine() {}

    public static boolean drivable(MovementLine line) {
        int d = decide(Baritone.settings().kinematicLine.value, line);
        int was = line.kinematicDecision;
        if (d != was) {
            DECISIONS[d].increment();
            if (was == ACCEPTED) {
                DOWNGRADES.increment();
            }
            line.kinematicDecision = d;
        }
        return d == ACCEPTED;
    }

    /** The decision alone, with no counting. */
    static int decide(boolean enabled, MovementLine line) {
        if (!enabled) {
            return OFF;
        }
        if (line.getSrc().y != line.getDest().y) {
            return NOT_FLAT;
        }
        if (line.plainGround()) {
            return ACCEPTED;
        }
        return line.groundChecked() ? GROUND : PENDING;
    }

    public static long decisions(int kind) {
        return DECISIONS[kind].sum();
    }

    public static long downgrades() {
        return DOWNGRADES.sum();
    }

    public static void reset() {
        for (LongAdder a : DECISIONS) {
            a.reset();
        }
        DOWNGRADES.reset();
    }
}
