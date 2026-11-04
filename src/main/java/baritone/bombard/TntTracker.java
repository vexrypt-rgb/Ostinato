/*
 * Ostinato: new file (Bombard TNT combat). Not part of upstream Baritone.
 */
package baritone.bombard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every primed TNT the client sees, from first sight to its end, and what is known of how it ended. Game-free,
 * fed once a tick on the game thread: {@link #begin}, {@link #see} for each TNT in view, {@link #end}; explode
 * packets through {@link #explosion}. Bounded: {@link #CAPACITY} entries, ended ones dropped after {@link #RETAIN}.
 * <p>
 * The client ticks a TNT's fuse itself and discards it at 0 without any word from the server (26.3
 * {@code PrimedTnt.tick}, verified), so a TNT vanishing is never evidence it exploded. Only an explode packet near
 * where and when its fuse ran out is ({@link Fate#EXPLODED}); a fuse running out with no packet is
 * {@link Fate#EXPIRED}, an explosion expected and not observed (gamerule off, or the packet out of its 64 blocks).
 * Ownership is not synced (the owner field is server-only), so {@link Cause#OURS} means it appeared where and when we
 * lit one, not that the server says so.
 */
public final class TntTracker {
    public static final int CAPACITY = 64, RETAIN = 40;
    /** An explode packet matches a TNT whose fuse ran out this many ticks either side of it. */
    public static final int MATCH_TICKS = 3;
    /** And within this distance of where it was last seen, plus the distance it was moving. */
    public static final double MATCH_DIST = 1.5;
    /** A blast lights TNT blocks it reaches; they show up a tick or two later (one-way latency aside). */
    public static final int CHAIN_TICKS = 3;

    public enum Fate {
        /** In view, fuse burning. */
        LIVE,
        /** An explode packet matched it: observed. */
        EXPLODED,
        /** Its fuse ran out and the client dropped it; no explode packet matched (yet). */
        EXPIRED,
        /** Removed before its fuse ran out (killed, unloaded, untracked): its end is unknown. */
        GONE_EARLY,
        /** Last seen at the edge of the scan: out of view, its end unknown. */
        LOST
    }

    public enum Cause {
        UNKNOWN,
        /** First seen where and when we lit a TNT block. */
        OURS,
        /** First seen just after an observed explosion that reached it, with a fuse a blast gives (10..29). */
        CHAIN
    }

    /** One TNT. Fields are the tracker's; read them, do not write them. */
    public static final class Tnt {
        public final int id;
        public final long firstTick;
        public final int firstFuse;
        long lastTick;
        int fuse;
        double x, y, z, vx, vy, vz;
        boolean ground;
        Fate fate = Fate.LIVE;
        Cause cause = Cause.UNKNOWN;
        long endTick = Long.MIN_VALUE;
        long fateEpoch, forecastEpoch;
        /** The explode packet matched, if any. */
        double bx = Double.NaN, by = Double.NaN, bz = Double.NaN;
        float power = Float.NaN;
        int blocks = -1;
        boolean hitUs;
        /** The forecast made at first sight, if any. */
        long forecastTick = Long.MIN_VALUE;
        double fx = Double.NaN, fy = Double.NaN, fz = Double.NaN;
        String forecastConfidence;

        Tnt(int id, long tick, int fuse) {
            this.id = id;
            this.firstTick = tick;
            this.firstFuse = fuse;
            this.lastTick = tick;
            this.fuse = fuse;
        }

        public long lastTick() { return lastTick; }
        public int fuse() { return fuse; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public double vx() { return vx; }
        public double vy() { return vy; }
        public double vz() { return vz; }
        public Fate fate() { return fate; }
        public Cause cause() { return cause; }
        public long endTick() { return endTick; }
        public float observedPower() { return power; }
        public boolean hitUs() { return hitUs; }

        /** The tick its fuse runs out on, from the newest sight. */
        public long dueTick() {
            return lastTick + TntMechanics.ticksLeft(fuse);
        }

        public boolean live() {
            return fate == Fate.LIVE;
        }
    }

    private record Ignition(long tick, double x, double y, double z) {}

    private record Blast(long tick, double x, double y, double z, float power) {}

    private final List<Tnt> tnt = new ArrayList<>();
    private final List<Ignition> ignitions = new ArrayList<>();
    private final List<Blast> blasts = new ArrayList<>();
    private long tick = Long.MIN_VALUE;
    private double ox, oy, oz, radius = Double.POSITIVE_INFINITY;
    private int seenThisTick;
    private long epoch;
    // counters: what was seen and how it ended, for the fight log
    long seen, overflow, unattributed, ours, chained;
    final long[] fates = new long[Fate.values().length];
    long forecasts, forecastsSettled;
    double forecastErrSum, forecastErrMax;
    long forecastTickErrSum, forecastTickErrMax;

    /** A tick starts; the observer is at {@code o} and sees TNT within {@code radius}. */
    public void begin(long tick, double ox, double oy, double oz, double radius) {
        this.tick = tick;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.radius = radius;
        seenThisTick = 0;
        ignitions.removeIf(i -> tick - i.tick > 40);
        blasts.removeIf(b -> tick - b.tick > CHAIN_TICKS + 40);
    }

    /** A primed TNT in view this tick. */
    public Tnt see(int id, double x, double y, double z, double vx, double vy, double vz, int fuse, boolean onGround) {
        Tnt t = find(id);
        if (t != null && !t.live()) {
            // entity ids are never reused in a server's run: an ended TNT seen again was misjudged
            if (t.fate == Fate.EXPLODED) return t; // the packet beat the client's own discard by a tick
            uncount(t);
            t.fate = Fate.LIVE;
            t.endTick = Long.MIN_VALUE;
        }
        if (t == null) {
            if (tnt.size() >= CAPACITY && !evictOne()) {
                overflow++;
                return null;
            }
            t = new Tnt(id, tick, fuse);
            tnt.add(t);
            seen++;
            t.x = x;
            t.y = y;
            t.z = z;
            t.cause = causeOf(t);
            if (t.cause == Cause.OURS) ours++;
            if (t.cause == Cause.CHAIN) chained++;
        }
        t.lastTick = tick;
        t.fuse = fuse;
        t.x = x;
        t.y = y;
        t.z = z;
        t.vx = vx;
        t.vy = vy;
        t.vz = vz;
        t.ground = onGround;
        seenThisTick++;
        return t;
    }

    /** The tick's sightings are in: anything live not seen this tick has ended, on its fuse or not. */
    public void end() {
        for (Tnt t : tnt) {
            if (!t.live() || t.lastTick == tick) continue;
            // the client drops a TNT the tick its fuse goes: due on (or before) this tick means its fuse ran out
            if (t.dueTick() <= tick) finish(t, Fate.EXPIRED, t.dueTick());
            else if (Math.sqrt(dist2(t.x, t.y, t.z, ox, oy, oz)) > radius - 2) finish(t, Fate.LOST, tick);
            else finish(t, Fate.GONE_EARLY, tick);
        }
        tnt.removeIf(t -> !t.live() && tick - t.endTick > RETAIN);
    }

    /**
     * An explode packet on this tick (drained on the game thread). It is matched to the TNT whose fuse ran out
     * nearest it in time and place; an explosion no TNT explains is counted unattributed (a creeper, a crystal, a
     * bed, TNT we never saw). Every explosion is kept a moment for {@link Cause#CHAIN}.
     */
    public Tnt explosion(double x, double y, double z, float power, int blocks, boolean hitUs) {
        if (blasts.size() >= CAPACITY) blasts.remove(0);
        blasts.add(new Blast(tick, x, y, z, power));
        Tnt best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Tnt t : tnt) {
            if (t.fate != Fate.LIVE && t.fate != Fate.EXPIRED) continue;
            if (Math.abs(t.dueTick() - tick) > MATCH_TICKS) continue;
            double speed = Math.sqrt(t.vx * t.vx + t.vy * t.vy + t.vz * t.vz);
            double d = Math.sqrt(dist2(t.x, t.y + TntMechanics.BLAST_Y, t.z, x, y, z));
            if (d > MATCH_DIST + speed * (MATCH_TICKS + 1)) continue;
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        if (best == null) {
            unattributed++;
            return null;
        }
        if (best.fate == Fate.EXPIRED) uncount(best);
        best.fate = Fate.LIVE; // finish() counts it again under its real fate
        finish(best, Fate.EXPLODED, tick);
        best.bx = x;
        best.by = y;
        best.bz = z;
        best.power = power;
        best.blocks = blocks;
        best.hitUs = hitUs;
        settleForecast(best);
        return best;
    }

    /** We lit (or tried to light) the TNT block at {@code (bx, by, bz)} on this tick: what appears there is ours. */
    public void ignited(int bx, int by, int bz) {
        if (ignitions.size() >= CAPACITY) ignitions.remove(0);
        ignitions.add(new Ignition(tick, bx + 0.5, by, bz + 0.5));
    }

    /** The engine's forecast for a TNT, made now: where and on which tick it explodes. */
    public void forecast(int id, long explodeTick, double x, double y, double z, String confidence) {
        Tnt t = find(id);
        if (t == null || !t.live() || t.forecastTick != Long.MIN_VALUE) return;
        t.forecastTick = explodeTick;
        t.forecastEpoch = epoch;
        t.fx = x;
        t.fy = y;
        t.fz = z;
        t.forecastConfidence = confidence;
        forecasts++;
    }

    private void settleForecast(Tnt t) {
        if (t.forecastTick == Long.MIN_VALUE || t.forecastEpoch != epoch) return;
        double e = Math.sqrt(dist2(t.fx, t.fy, t.fz, t.bx, t.by, t.bz));
        long te = Math.abs(t.forecastTick - t.endTick);
        forecastsSettled++;
        forecastErrSum += e;
        forecastErrMax = Math.max(forecastErrMax, e);
        forecastTickErrSum += te;
        forecastTickErrMax = Math.max(forecastTickErrMax, te);
    }

    private Cause causeOf(Tnt t) {
        // TntBlock.prime: at the block's centre, 0.2 up and 0.02 aside a tick, so it is near the block's centre for a while
        for (Ignition i : ignitions) {
            long dt = tick - i.tick;
            if (dt < 0 || dt > 40) continue;
            if (Math.abs(t.x - i.x) < 0.5 && Math.abs(t.z - i.z) < 0.5 && t.y > i.y - 0.5 && t.y < i.y + 1.5
                    && t.firstFuse + dt >= TntMechanics.DEFAULT_FUSE - 1) return Cause.OURS;
        }
        for (Blast b : blasts) {
            long dt = tick - b.tick;
            if (dt < 0 || dt > CHAIN_TICKS) continue;
            // the new TNT's add packet can trail the explode packet by a server tick, or its first tick can be counted
            if (!TntMechanics.chainLit(t.firstFuse, (int) dt) && !TntMechanics.chainLit(t.firstFuse, (int) dt + 1)) continue;
            if (Math.sqrt(dist2(t.x, t.y, t.z, b.x, b.y, b.z)) <= TntMechanics.damageRadius(b.power) + 1) return Cause.CHAIN;
        }
        return Cause.UNKNOWN;
    }

    private void finish(Tnt t, Fate f, long when) {
        t.fateEpoch = epoch;
        t.fate = f;
        t.endTick = when;
        fates[f.ordinal()]++;
    }

    /** Take back a fate counted in error; one counted before {@link #clearCounters} is not this fight's to take back. */
    private void uncount(Tnt t) {
        if (t.fateEpoch == epoch && fates[t.fate.ordinal()] > 0) fates[t.fate.ordinal()]--;
    }

    private boolean evictOne() {
        Tnt oldest = null;
        for (Tnt t : tnt) if (!t.live() && (oldest == null || t.endTick < oldest.endTick)) oldest = t;
        if (oldest == null) return false;
        tnt.remove(oldest);
        return true;
    }

    private Tnt find(int id) {
        for (Tnt t : tnt) if (t.id == id) return t;
        return null;
    }

    private static double dist2(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Every TNT still tracked, live and recently ended; read-only. */
    public List<Tnt> all() {
        return Collections.unmodifiableList(tnt);
    }

    /** Live TNT, soonest due first. */
    public List<Tnt> live() {
        List<Tnt> out = new ArrayList<>();
        for (Tnt t : tnt) if (t.live()) out.add(t);
        out.sort((a, b) -> Long.compare(a.dueTick(), b.dueTick()));
        return out;
    }

    public Tnt get(int id) {
        return find(id);
    }

    public int seenThisTick() {
        return seenThisTick;
    }

    public long fate(Fate f) {
        return fates[f.ordinal()];
    }

    /** World changed: nothing seen there is still in view. Counters are kept. */
    public void forget() {
        for (Tnt t : tnt) if (t.live()) finish(t, Fate.LOST, tick);
        tnt.clear();
        ignitions.clear();
        blasts.clear();
    }

    /** One line for the fight log; empty when nothing was seen. */
    public String report(String label) {
        if (seen == 0 && unattributed == 0 && java.util.Arrays.stream(fates).sum() == 0) return "";
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "bombard tnt %s seen=%d ours=%d chain=%d", label, seen, ours, chained));
        for (Fate f : Fate.values()) if (f != Fate.LIVE) b.append(' ').append(f.name().toLowerCase(Locale.ROOT)).append('=').append(fates[f.ordinal()]);
        b.append(String.format(Locale.ROOT, " unattributed=%d overflow=%d", unattributed, overflow));
        if (forecasts > 0) {
            b.append(String.format(Locale.ROOT, " | forecast n=%d settled=%d", forecasts, forecastsSettled));
            if (forecastsSettled > 0) b.append(String.format(Locale.ROOT, " pos mean=%.3f max=%.3f tick mean=%.2f max=%d",
                    forecastErrSum / forecastsSettled, forecastErrMax, (double) forecastTickErrSum / forecastsSettled, forecastTickErrMax));
        }
        return b.toString();
    }

    /** Counters back to zero (a new fight); tracked TNT are kept, they are still burning. */
    public void clearCounters() {
        epoch++;
        seen = overflow = unattributed = ours = chained = 0;
        java.util.Arrays.fill(fates, 0);
        forecasts = forecastsSettled = 0;
        forecastErrSum = forecastErrMax = 0;
        forecastTickErrSum = forecastTickErrMax = 0;
    }
}
