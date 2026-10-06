package baritone.process;

/**
 * The tunable numbers behind combat decisions, as plain fields. Each one has a default (what the fights taught), a
 * hard min and max, and can be overridden for a run with {@code -Dostinato.policy.<group>.<name>=value} so a sweep
 * needs no rebuild. Values are fixed for the life of the run, so a seeded bench stays deterministic. The comment
 * beside a number says what it measures; the fight logs it came from stay in the comments at the use site.
 * A number that should follow the opponent (their swing gap, say) is read from {@link PvpOpponent} instead.
 */
final class CombatPolicy {
    final Spear spear = new Spear();

    /** Spear: when to jab, when to start a charge, and the window in which a released charge pierces. */
    static final class Spear {
        /** Horizontal speed under which a jab is taken; faster slides miss. */
        final double jabSlide = param("spear.jabSlide", 0.03, 0.0, 0.1);
        /** Inside this distance with a jab not ready, wait up to jabWaitTicks and then back out. */
        final double jabBand = param("spear.jabBand", 4.8, 3.5, 6.0);
        final int jabWaitTicks = (int) param("spear.jabWaitTicks", 30, 5, 80);
        /** A committed back-out ends after this many ticks, or once closer than commitHr. */
        final int commitTicks = (int) param("spear.commitTicks", 36, 10, 100);
        final double commitHr = param("spear.commitHr", 3.2, 2.0, 4.5);
        /** Give up reopening range after this many ticks. */
        final int reopenTicks = (int) param("spear.reopenTicks", 70, 20, 200);
        /** Distance at which the back-out turns to face the target. */
        final double faceHr = param("spear.faceHr", 7.4, 5.0, 10.0);
        /** Aim tolerance in degrees before a run-up, and the looser one after faceSlowTicks. */
        final float faceTol = (float) param("spear.faceTol", 25, 10, 45);
        final float faceTolLate = (float) param("spear.faceTolLate", 50, 25, 90);
        /** Kinetic speed along the line (blocks/s) needed for a charge to pierce, and the floor under which it is abandoned. */
        final double chargeAlong = param("spear.chargeAlong", 4.6, 3.0, 6.0);
        final double failAlong = param("spear.failAlong", 4.2, 2.5, 5.5);
        /** Predicted distance at tick 10 must fall in this window to start a charge. */
        final double at10Lo = param("spear.at10Lo", 2.4, 1.5, 3.5);
        final double at10Hi = param("spear.at10Hi", 4.4, 3.0, 5.0);
        /** Charge may start between startHrMin and startHrMax. */
        final double startHrMin = param("spear.startHrMin", 4.55, 3.5, 6.0);
        final double startHrMax = param("spear.startHrMax", 14, 8, 20);
        /** Earliest release tick, and the distance window in which the release pierces. */
        final int minUseTicks = (int) param("spear.minUseTicks", 11, 8, 20);
        final double pierceLo = param("spear.pierceLo", 2.05, 1.5, 3.0);
        final double pierceHi = param("spear.pierceHi", 4.5, 3.5, 5.5);
        /** A charge is given up beyond this distance or after this many ticks. */
        final double failHr = param("spear.failHr", 16, 10, 24);
        final int maxUseTicks = (int) param("spear.maxUseTicks", 200, 50, 400);
        /** Ticks to wait after a charge before another. */
        final int useCool = (int) param("spear.useCool", 8, 0, 40);
    }

    private static double param(String name, double def, double min, double max) {
        String v = System.getProperty("ostinato.policy." + name);
        if (v == null) return def;
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(v)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
