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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: decides by itself who in render distance is fighting, with no spectate command and no server menu.
 * Pure (no Minecraft types) so it is unit-tested. Each tick the learner feeds every visible player. A pair is
 * engaged only when they are close and one of them swings while facing the other, is hurt, or takes a knockback
 * shove. The fight stays up through short pauses, and ends when it resolves, they walk apart, or someone leaves
 * the snapshot (left render distance). A ledge shove counts when the victim then dies, falls, or drops below the
 * arena; the winner is the last attacker inside {@link #ATTRIB_TICKS}, not whoever touched them earlier in the bout.
 */
public final class FightDetector {

    /** One visible player this tick. {@code hurt}: hurt animation started / health dropped. */
    public record Obs(int id, double x, double y, double z, float yaw, boolean hurt, boolean swinging, boolean knocked, boolean dead) {
        public Obs(int id, double x, double y, double z, float yaw, boolean hurt, boolean swinging) {
            this(id, x, y, z, yaw, hurt, swinging, false, false);
        }
    }

    /** Ostinato: a fight end. {@code winner} is -1 when the last attacker is outside {@link #ATTRIB_TICKS}. */
    public record Outcome(int loser, int winner, String cause) {}

    /** Ostinato: a no-damage shove (sumo hit): the victim was knocked while the attacker swung at them in reach. */
    public record Contact(int attacker, int victim) {}

    /** How long a pair stays engaged after its last combat sign, so strafing and healing do not end the recording. */
    public static final int HOLD_TICKS = 200;
    /**
     * Ostinato: kill credit for a knock-off, fall, or void death. 100 ticks is long enough for a fall off a high
     * ledge and short enough that an earlier opponent does not inherit a later death.
     */
    public static final int ATTRIB_TICKS = 100;
    /** Melee reach plus slack for latency. */
    static final double SWING_REACH = 4.5;
    /** A hurt or knocked player counts the nearest player within this as the likely attacker. */
    static final double HURT_NEAR = 6.0;
    /** cos of the cone a swing must face. */
    static final double FACING_COS = 0.5;
    /** Ostinato: blocks below the y where the fight started. Dropping this far is a ledge knock-off, dead or not. */
    public static final double ARENA_DROP = 8.0;
    /** Ostinato: walking this far apart, while neither is falling, ends the fight with no winner. */
    public static final double DISENGAGE = 16.0;
    /** Horizontal speed squared that counts as a knockback spike, against the previous sample. */
    static final double KNOCK_H2 = 0.09;

    private double voidY = Double.NEGATIVE_INFINITY;
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Map<Integer, Credit> credits = new HashMap<>();
    private final ArrayList<Outcome> outcomes = new ArrayList<>();
    private final ArrayList<Contact> contacts = new ArrayList<>();

    /** Below this y an engaged player counts as knocked into the void. */
    public void voidY(double y) {
        voidY = y;
    }

    /** Sumo hits seen since the last call (damaging hits arrive through the normal damage path instead). */
    public List<Contact> drainContacts() {
        List<Contact> c = List.copyOf(contacts);
        contacts.clear();
        return c;
    }

    /** Fight ends seen since the last call. */
    public List<Outcome> drainOutcomes() {
        List<Outcome> o = List.copyOf(outcomes);
        outcomes.clear();
        return o;
    }

    /**
     * Ostinato: last attacker of {@code id} still inside {@link #ATTRIB_TICKS} at {@code now}, or -1.
     * Survives the player leaving the snapshot, so a death packet in the same window still has a name.
     */
    public int credit(int id, long now) {
        Credit c = credits.get(id);
        if (c == null || now - c.tick > ATTRIB_TICKS) return -1;
        return c.foe;
    }

    /** Ostinato: the credit above came from a knockback, not only from a hurt or a swing. */
    public boolean creditFromKnock(int id, long now) {
        Credit c = credits.get(id);
        return c != null && c.knock && now - c.tick <= ATTRIB_TICKS && c.foe >= 0;
    }

    /**
     * Ostinato: a sudden horizontal shove. {@code prevH2} is null on the first sample, which is never a knock.
     * Sprint sits under {@link #KNOCK_H2}; vanilla knockback clears it and is a sharp jump from the previous tick.
     */
    public static boolean velocityKnock(Double prevH2, double h2) {
        return prevH2 != null && h2 > KNOCK_H2 && h2 > prevH2 * 2.5;
    }

    /** Feeds one tick; returns nothing, query with {@link #engaged}. */
    public void tick(long tick, List<Obs> players) {
        Map<Integer, Obs> byId = new HashMap<>();
        for (Obs p : players) byId.put(p.id, p);
        Set<Integer> resolved = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            Obs a = players.get(i);
            if (!a.hurt && !a.swinging && !a.knocked) continue;
            Obs best = null;
            double bestD = Double.MAX_VALUE;
            for (Obs b : players) {
                if (b.id == a.id) continue;
                double d = dist(a, b);
                if (a.swinging && d <= SWING_REACH && facing(a, b) && d < bestD) {
                    best = b;
                    bestD = d;
                } else if ((a.hurt || a.knocked) && d <= HURT_NEAR && d < bestD) {
                    best = b;
                    bestD = d;
                }
            }
            if (best == null) continue;
            touch(tick, a, best.id, a.knocked);
            touch(tick, best, a.id, false);
            // Ostinato: the shove itself is a hit when no health was lost. A damaging hit is recorded elsewhere.
            if (a.knocked && !a.hurt && best.swinging && dist(a, best) <= SWING_REACH + 1) contacts.add(new Contact(best.id, a.id));
        }
        for (Obs a : players) {
            Track t = tracks.get(a.id);
            if (t == null) continue;
            double dy = a.y - t.lastY;
            String cause = cause(a, t, dy);
            if (cause != null) {
                emit(tick, a.id, cause);
                resolved.add(a.id);
                continue;
            }
            t.lastDy = dy;
            t.lastY = a.y;
        }
        for (int id : new ArrayList<>(tracks.keySet())) {
            Track t = tracks.get(id);
            if (t != null && resolved.contains(t.foe)) tracks.remove(id);
        }
        for (int id : new ArrayList<>(tracks.keySet())) {
            Track t = tracks.get(id);
            if (t == null) continue;
            if (byId.containsKey(id)) continue;
            int foe = t.foe;
            boolean fallingOff = t.lastDy < -0.4 || t.lastY < t.engageY - ARENA_DROP || t.lastY < voidY;
            if (fallingOff) emit(tick, id, t.lastY < voidY ? "void" : "knockoff");
            else tracks.remove(id);
            Track other = tracks.get(foe);
            if (other != null && other.foe == id) tracks.remove(foe);
        }
        for (int id : new ArrayList<>(tracks.keySet())) {
            Track t = tracks.get(id);
            if (t == null || t.foe < 0) continue;
            Obs self = byId.get(id);
            Obs foe = byId.get(t.foe);
            Track other = tracks.get(t.foe);
            if (self == null || foe == null) continue;
            if (dist(self, foe) <= DISENGAGE) continue;
            boolean airborne = t.lastDy < -0.4 || (other != null && other.lastDy < -0.4);
            if (airborne) continue;
            tracks.remove(id);
            if (other != null && other.foe == id) tracks.remove(t.foe);
        }
        tracks.values().removeIf(t -> t.until < tick);
    }

    public boolean engaged(int id) {
        return tracks.containsKey(id);
    }

    public boolean anyFight() {
        return !tracks.isEmpty();
    }

    public Set<Integer> engagedIds() {
        return tracks.keySet();
    }

    public void clear() {
        tracks.clear();
        credits.clear();
        outcomes.clear();
        contacts.clear();
    }

    private void touch(long tick, Obs who, int foe, boolean knock) {
        Track t = tracks.computeIfAbsent(who.id, id -> new Track(who.y));
        t.until = tick + HOLD_TICKS;
        t.foe = foe;
        t.foeTick = tick;
        if (knock) t.knockedTick = tick;
        Credit prev = credits.get(who.id);
        boolean stillKnock = knock || (prev != null && prev.knock && tick - prev.tick <= ATTRIB_TICKS);
        credits.put(who.id, new Credit(foe, tick, stillKnock));
    }

    private void emit(long tick, int id, String cause) {
        Track t = tracks.remove(id);
        if (t == null) return;
        int winner = t.foe >= 0 && tick - t.foeTick <= ATTRIB_TICKS ? t.foe : -1;
        outcomes.add(new Outcome(id, winner, cause));
    }

    /** {@code null} when this player is still fighting. */
    private String cause(Obs a, Track t, double dy) {
        if (a.y < voidY) return "void";
        if (a.y < t.engageY - ARENA_DROP) return "knockoff";
        if (a.dead && dy < -0.5) return "fall";
        if (a.dead) return "kill";
        return null;
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

    private static final class Track {
        final double engageY;
        long until, foeTick = -1_000_000, knockedTick = -1_000_000;
        int foe = -1;
        double lastY, lastDy;

        Track(double y) {
            engageY = lastY = y;
        }
    }

    private record Credit(int foe, long tick, boolean knock) {}
}
