/*
 * Ostinato: new file (cheater detection in spectate learning). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Ostinato: flags spectated players whose play breaks what a vanilla client can do, so their fights stay out of
 * what the bot learns. Watch-only: it never reports anyone and never acts on a server; it only labels fights.
 * <p>
 * Every check is conservative: a single odd tick (lag, a missed packet, a server teleport) never flags anyone. A
 * reason is given only once a check has seen at least its minimum number of violations, and for the per-hit checks
 * also a minimum share of the player's hits. Pure: fed plain numbers by {@link SpectateLearner} on the game thread.
 */
public final class CheatWatch {

    // reach: eye to the victim's box on a landed melee hit; vanilla allows 3.0, a little lag is forgiven
    public static final double REACH = 3.1;
    public static final int REACH_MIN = 4;
    // aim: a landed hit on someone more than FOV_OFF degrees off their facing, or after a snap of SNAP degrees in a tick
    public static final double FOV_OFF = 60, SNAP = 100;
    public static final int AIM_MIN = 3;
    // clicking: more than CPS_MAX swings in any one second, or CPS_RUN intervals with almost no spread (an autoclicker)
    public static final int CPS_MAX = 16, CPS_MIN = 3, CPS_RUN = 30;
    public static final double CPS_SPREAD = 0.3;
    // movement: on the ground faster than SPEED blocks a tick; hovering FLY_TICKS in the air without falling
    public static final double SPEED = 0.9, TELEPORT = 4.0;
    public static final int SPEED_MIN = 10, FLY_TICKS = 20, FLY_MIN = 2, KB_GRACE = 20;
    // no knockback: a melee hit that moves them less than KB_MOVE over KB_TICKS
    public static final double KB_MOVE = 0.05;
    public static final int KB_TICKS = 3, KB_MIN = 4;
    // reaction: a hit landed the very tick the victim came into reach and view, right after a turn of REACT_TURN degrees
    public static final double REACT_TURN = 30;
    public static final int REACT_MIN = 5;

    static final class P {
        int hits, reach, aim, cps, speed, fly, kbSeen, kbNone, react;
        double maxReach;
        // movement state
        boolean have;
        double x, y, z;
        float yaw, lastTurn;
        int lastTick, air, hover, lastHurt = -1000000, lastExempt = -1000000;
        // swing history
        final ArrayDeque<Integer> swings = new ArrayDeque<>();
        int lastSwing = Integer.MIN_VALUE, cpsWindowCount;
        final ArrayDeque<Integer> gaps = new ArrayDeque<>();
        // knockback probe: tick of the hit and where they stood
        int kbTick = -1;
        double kbX, kbZ;
    }

    private final Map<Integer, P> players = new HashMap<>();

    private P p(int id) {
        return players.computeIfAbsent(id, k -> new P());
    }

    public void forget(int id) {
        players.remove(id);
    }

    public void clear() {
        players.clear();
    }

    /**
     * One tick of a player's movement. {@code exempt} covers whatever vanilla lets move oddly: elytra, riding,
     * levitation, slow falling, speed or jump boost, water, lava, ladders, creative or spectator.
     */
    public void move(int id, int tick, double x, double y, double z, float yaw, boolean onGround, boolean exempt, boolean hurtNow) {
        P s = p(id);
        if (hurtNow) s.lastHurt = tick;
        if (exempt) s.lastExempt = tick;
        if (!s.have || tick - s.lastTick != 1) { // first sight, or a gap: no deltas from it
            s.have = true;
            s.air = s.hover = 0;
            setPos(s, tick, x, y, z, yaw);
            return;
        }
        double dx = x - s.x, dy = y - s.y, dz = z - s.z, h = Math.sqrt(dx * dx + dz * dz);
        s.lastTurn = Math.abs(wrap(yaw - s.yaw));
        boolean free = tick - s.lastExempt > KB_GRACE && tick - s.lastHurt > KB_GRACE; // knockback and effects wear off
        if (h > TELEPORT) { // a server teleport, never a cheat by itself
            s.air = s.hover = 0;
        } else if (free) {
            if (onGround && h > SPEED) s.speed++;
            if (!onGround) {
                s.air++;
                if (Math.abs(dy) < 0.03) s.hover++;
                else s.hover = 0;
                if (s.hover >= FLY_TICKS) {
                    s.fly++;
                    s.hover = 0;
                }
            } else {
                s.air = s.hover = 0;
            }
        } else {
            s.hover = 0;
        }
        // knockback probe
        if (s.kbTick >= 0 && tick - s.kbTick >= KB_TICKS) {
            if (tick - s.lastExempt > KB_GRACE) {
                s.kbSeen++;
                if (Math.hypot(x - s.kbX, z - s.kbZ) < KB_MOVE) s.kbNone++;
            }
            s.kbTick = -1;
        }
        setPos(s, tick, x, y, z, yaw);
    }

    private static void setPos(P s, int tick, double x, double y, double z, float yaw) {
        s.x = x;
        s.y = y;
        s.z = z;
        s.yaw = yaw;
        s.lastTick = tick;
    }

    /** A swing (arm animation) seen from a player. */
    public void swing(int id, int tick) {
        P s = p(id);
        if (s.lastSwing != Integer.MIN_VALUE) {
            int gap = tick - s.lastSwing;
            if (gap > 0 && gap <= 10) { // only clicks in a burst count toward the spread
                s.gaps.addLast(gap);
                if (s.gaps.size() > CPS_RUN) s.gaps.removeFirst();
                if (s.gaps.size() == CPS_RUN && spread(s.gaps) < CPS_SPREAD && mean(s.gaps) <= 4) { // 5+ CPS like a metronome
                    s.cps++;
                    s.gaps.clear();
                }
            } else if (gap > 10) {
                s.gaps.clear();
            }
        }
        s.lastSwing = tick;
        s.swings.addLast(tick);
        while (!s.swings.isEmpty() && tick - s.swings.peekFirst() >= 20) s.swings.removeFirst();
        if (s.swings.size() > CPS_MAX) {
            s.cps++;
            s.swings.clear();
        }
    }

    /**
     * A landed melee hit. {@code reach}: attacker's eye to the victim's box; {@code off}: degrees between the
     * attacker's facing and the victim; {@code sinceAvailable}: ticks since the victim came into reach and view of the
     * attacker (0: this very tick).
     */
    public void hit(int attacker, int victim, int tick, double reach, double off, int sinceAvailable) {
        P a = p(attacker);
        a.hits++;
        if (reach > REACH) {
            a.reach++;
            a.maxReach = Math.max(a.maxReach, reach);
        }
        if (off > FOV_OFF || a.lastTick == tick && a.lastTurn > SNAP) a.aim++;
        if (sinceAvailable == 0 && a.lastTick == tick && a.lastTurn > REACT_TURN) a.react++;
        P v = p(victim);
        if (v.have && v.kbTick < 0) {
            v.kbTick = tick;
            v.kbX = v.x;
            v.kbZ = v.z;
        }
    }

    /** Why this player looks like a cheater, or null. Short and space-free, for the fight log. */
    public String reason(int id) {
        P s = players.get(id);
        if (s == null) return null;
        StringJoiner j = new StringJoiner(",");
        if (s.reach >= REACH_MIN && s.reach * 4 >= s.hits) j.add(String.format(Locale.ROOT, "reach%dx%.2f", s.reach, s.maxReach));
        if (s.aim >= AIM_MIN && s.aim * 5 >= s.hits) j.add("aim" + s.aim + "x");
        if (s.cps >= CPS_MIN) j.add("cps" + s.cps + "x");
        if (s.speed >= SPEED_MIN) j.add("speed" + s.speed + "t");
        if (s.fly >= FLY_MIN) j.add("fly" + s.fly + "x");
        if (s.kbNone >= KB_MIN && s.kbNone * 2 >= s.kbSeen) j.add("nokb" + s.kbNone + "/" + s.kbSeen);
        if (s.react >= REACT_MIN && s.react * 5 >= s.hits * 2) j.add("react" + s.react + "x");
        return j.length() == 0 ? null : j.toString();
    }

    static double mean(Iterable<Integer> xs) {
        double s = 0;
        int n = 0;
        for (int x : xs) {
            s += x;
            n++;
        }
        return n == 0 ? 0 : s / n;
    }

    static double spread(Iterable<Integer> xs) {
        double m = mean(xs), s = 0;
        int n = 0;
        for (int x : xs) {
            s += (x - m) * (x - m);
            n++;
        }
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    static float wrap(float d) {
        d %= 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** Degrees between a facing (Minecraft yaw) and the direction from (fx, fz) to (tx, tz). */
    public static double offAngle(float yaw, double fx, double fz, double tx, double tz) {
        double want = Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
        return Math.abs(wrap((float) (yaw - want)));
    }
}
