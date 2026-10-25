/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.spectate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: decides by itself who in render distance is fighting, with no spectate command and no server menu.
 * Pure (no Minecraft types) so it is unit-tested. Each tick the learner feeds every visible player; a pair is
 * engaged when one of them was just hurt with the other close by, or one swings while facing the other in reach.
 * Engagement is held for {@link #HOLD_TICKS} so a fight's pauses (strafing, pearls, healing) don't end it.
 */
public final class FightDetector {

    /** One visible player this tick. {@code hurt}: hurt animation started / health dropped. */
    public record Obs(int id, double x, double y, double z, float yaw, boolean hurt, boolean swinging, boolean knocked, boolean dead) {
        public Obs(int id, double x, double y, double z, float yaw, boolean hurt, boolean swinging) {
            this(id, x, y, z, yaw, hurt, swinging, false, false);
        }
    }

    /** Ostinato: a fight end: loser died (any cause, incl. void / fall after a knock with no damage), winner = last foe. */
    public record Outcome(int loser, int winner, String cause) {}

    /** Below this y (relative to world bottom passed in) an engaged player counts as knocked into the void. */
    private double voidY = Double.NEGATIVE_INFINITY;
    private final Map<Integer, Integer> lastFoe = new HashMap<>();
    private final java.util.ArrayList<Outcome> outcomes = new java.util.ArrayList<>();

    public void voidY(double y) {
        voidY = y;
    }

    /** Fight ends seen since the last call. */
    public List<Outcome> drainOutcomes() {
        List<Outcome> o = List.copyOf(outcomes);
        outcomes.clear();
        return o;
    }

    /** How long a pair stays engaged after its last combat sign. */
    public static final int HOLD_TICKS = 200;
    /** Melee reach plus slack for latency. */
    static final double SWING_REACH = 4.5;
    /** A hurt player counts the nearest player within this as the likely attacker (knockback, bows close in). */
    static final double HURT_NEAR = 6.0;
    /** cos of the cone a swing must face. */
    static final double FACING_COS = 0.5;

    private final Map<Integer, Long> engagedUntil = new HashMap<>();

    /** Feeds one tick; returns nothing, query with {@link #engaged}. */
    public void tick(long tick, List<Obs> players) {
        for (int i = 0; i < players.size(); i++) {
            Obs a = players.get(i);
            // Ostinato: sumo: knockback with no/low damage still engages, as does a swing in reach
            if (!a.hurt && !a.swinging && !a.knocked) continue;
            Obs best = null;
            double bestD = Double.MAX_VALUE;
            for (Obs b : players) {
                if (b == a) continue;
                double d = dist(a, b);
                if (a.swinging && d <= SWING_REACH && facing(a, b) && d < bestD) {
                    best = b;
                    bestD = d;
                } else if ((a.hurt || a.knocked) && d <= HURT_NEAR && d < bestD) {
                    best = b;
                    bestD = d;
                }
            }
            if (best != null) {
                engagedUntil.put(a.id, tick + HOLD_TICKS);
                engagedUntil.put(best.id, tick + HOLD_TICKS);
                lastFoe.put(a.id, best.id);
                lastFoe.put(best.id, a.id);
            }
        }
        // Ostinato: win condition: an engaged player dies or drops into the void
        for (Obs a : players) {
            if (!engagedUntil.containsKey(a.id)) continue;
            boolean fell = a.y < voidY;
            if (a.dead || fell) {
                Integer w = lastFoe.remove(a.id);
                outcomes.add(new Outcome(a.id, w == null ? -1 : w, fell ? "void" : a.hurt ? "kill" : "fall"));
                engagedUntil.remove(a.id);
            }
        }
        engagedUntil.values().removeIf(u -> u < tick);
        lastFoe.keySet().retainAll(engagedUntil.keySet());
    }

    public boolean engaged(int id) {
        return engagedUntil.containsKey(id);
    }

    public boolean anyFight() {
        return !engagedUntil.isEmpty();
    }

    public Set<Integer> engagedIds() {
        return engagedUntil.keySet();
    }

    public void clear() {
        engagedUntil.clear();
        lastFoe.clear();
        outcomes.clear();
    }

    private static double dist(Obs a, Obs b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Minecraft yaw: 0 faces +z, 90 faces -x. */
    private static boolean facing(Obs a, Obs b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) return true; // on top of each other: any swing counts
        double r = Math.toRadians(a.yaw);
        return (-Math.sin(r) * dx + Math.cos(r) * dz) / len >= FACING_COS;
    }
}
