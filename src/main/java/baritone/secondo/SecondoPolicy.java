/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.secondo;

/**
 * Ostinato: pure companion policy. No world, no path search. The process turns the decision into a goal.
 * <p>
 * Owned here: who to stick to, and whether this tick is follow, a nearby break, a drop, or a fight.
 * Not owned: movement execution, mining search, combat, PPE, Grace Note, Cadenza, Bombard, Scherzo, Presto.
 */
public final class SecondoPolicy {

    public enum Mode { OFF, FOLLOW, ASSIST_BREAK, ASSIST_PICKUP, ASSIST_COMBAT }

    /** What the process may ask an existing system to do. v1 only paths; BREAK and COMBAT are recorded, not executed. */
    public enum Request { NONE, BREAK, PICKUP, COMBAT }

    /**
     * Mirrors the {@code secondo*} settings. Distances are clamped so a {@code GoalNear} heuristic cannot scan a huge box.
     *
     * @param followDistance GoalNear range around Primo, blocks
     * @param assistRadius   break or the policy ignores it when the block is farther than this from Secondo
     * @param assistStand    GoalNear range around the block Primo is breaking
     * @param pickupRadius   drop must be at most this far from Secondo
     */
    public record Config(
            boolean enabled,
            int followDistance,
            boolean assist,
            int assistRadius,
            int assistStand,
            boolean pickup,
            int pickupRadius,
            boolean combatAssist
    ) {
        /** Defaults match Settings.java. Assist paths are off; pickup is armed for when assist is turned on. */
        public static Config defaults() {
            return new Config(false, 4, false, 6, 2, true, 6, false);
        }

        public Config enabled(boolean v) {
            return new Config(v, followDistance, assist, assistRadius, assistStand, pickup, pickupRadius, combatAssist);
        }

        public Config assist(boolean v) {
            return new Config(enabled, followDistance, v, assistRadius, assistStand, pickup, pickupRadius, combatAssist);
        }

        public Config pickup(boolean v) {
            return new Config(enabled, followDistance, assist, assistRadius, assistStand, v, pickupRadius, combatAssist);
        }

        public Config combat(boolean v) {
            return new Config(enabled, followDistance, assist, assistRadius, assistStand, pickup, pickupRadius, v);
        }

        public Config clamped() {
            return new Config(enabled, clamp(followDistance, 0, 16), assist, clamp(assistRadius, 1, 32),
                    clamp(assistStand, 0, 8), pickup, clamp(pickupRadius, 1, 32), combatAssist);
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    /**
     * One tick of facts. Positions are block coordinates. Break and drop fields are ignored unless their flags are set.
     *
     * @param threat name of whoever Primo is fighting, or null
     */
    public record Observation(
            boolean bound,
            boolean primoAlive,
            int selfX, int selfY, int selfZ,
            int primoX, int primoY, int primoZ,
            boolean breaking,
            int breakX, int breakY, int breakZ,
            boolean drop,
            int dropX, int dropY, int dropZ,
            boolean fighting,
            String threat
    ) {
        public static Observation follow(int selfX, int selfY, int selfZ, int primoX, int primoY, int primoZ) {
            return new Observation(true, true, selfX, selfY, selfZ, primoX, primoY, primoZ,
                    false, 0, 0, 0, false, 0, 0, 0, false, null);
        }
    }

    /**
     * @param range GoalNear radius, or unused when the mode is a GoalBlock pickup
     * @param detail threat name, or empty
     */
    public record Decision(Mode mode, Request request, int x, int y, int z, int range, String detail) {
        public static Decision off() {
            return new Decision(Mode.OFF, Request.NONE, 0, 0, 0, 0, "");
        }

        public boolean paths() {
            return mode != Mode.OFF;
        }
    }

    private SecondoPolicy() {
    }

    public static Decision decide(Config raw, Observation o) {
        Config c = raw.clamped();
        if (!c.enabled || !o.bound || !o.primoAlive) {
            return Decision.off();
        }
        if (c.assist && c.combatAssist && o.fighting) {
            return new Decision(Mode.ASSIST_COMBAT, Request.COMBAT, o.primoX, o.primoY, o.primoZ, c.followDistance,
                    o.threat == null ? "" : o.threat);
        }
        if (c.assist && o.breaking && dist(o.selfX, o.selfY, o.selfZ, o.breakX, o.breakY, o.breakZ) <= c.assistRadius) {
            return new Decision(Mode.ASSIST_BREAK, Request.BREAK, o.breakX, o.breakY, o.breakZ, c.assistStand, "");
        }
        if (c.assist && c.pickup && o.drop && dist(o.selfX, o.selfY, o.selfZ, o.dropX, o.dropY, o.dropZ) <= c.pickupRadius) {
            return new Decision(Mode.ASSIST_PICKUP, Request.PICKUP, o.dropX, o.dropY, o.dropZ, 0, "");
        }
        return new Decision(Mode.FOLLOW, Request.NONE, o.primoX, o.primoY, o.primoZ, c.followDistance, "");
    }

    static double dist(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
