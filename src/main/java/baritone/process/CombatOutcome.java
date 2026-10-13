package baritone.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the fights are worth under one setting of the tuned numbers: the damage our clicks did and the damage we took, per tick spent
 * fighting. It is counted while the fight happens and is never fed back into it. It reads the numbers the recorder
 * writes (both sides' health and absorption, the foe's hurt flash, our click count) and changes no decision.
 * <p>
 * A tick is <em>active</em> when the fight body ran on it and on the tick before, against the same foe, with both of
 * us alive on the tick before, every health reading a finite number, and the foe within {@link Rules#ACTIVE_DIST}.
 * None of that reads a policy value. Active ticks are the denominator of both rates.
 * <p>
 * An engagement starts on the first active tick, with the kit carried then, and lasts until we die or the fight
 * stops. Its ticks are gathered into windows of {@link Rules#WINDOW} active ticks. A window also ends when the
 * setting moves, we die, the kind of foe changes or the fight stops, and is kept whatever it holds. Every window is
 * written to the fight log as one line and added to a running total for its kit, kind of foe and setting:
 * <pre>
 * acs ctx=sword+shield/player set=base n=200 wall=213 dealt=20.50 drop=21.00 taken=14.20 out=0.00
 *     clicks=31 flashes=18 kills=0 deaths=0 seq=0 end=full
 * </pre>
 * {@code set} is the setting as {@link CombatPolicy#setting} names it; {@code n} active ticks out of {@code wall}
 * fight ticks; {@code dealt} is the foe's health lost within {@link Rules#CLICK_TICKS} ticks of a click of ours and
 * {@code drop} all it lost on active ticks; {@code taken} is our own loss on active ticks and {@code out} on the
 * others; {@code seq} counts the engagement's windows from 0, so the opening of a fight can be told from the rest
 * of it. {@code unsynced} at the end marks a window in which the foe flashed hurt and its health never moved: the
 * server is not showing it, and dealt means nothing there.
 * <p>
 * An engagement is written down whole as well when it ends, as a {@link Fight}: how it ended, and how far it moved the
 * two of us apart. That is what the numbers a fight is fought whole under are judged by ({@link CombatFightTuner}):
 * <pre>
 * acs-fight ctx=sword+shield/player stance=base has=apple end=win wall=612 n=540 score=0.650 foes=1.000 mine=0.350
 *     least=0.30 kills=1 spent=2
 * </pre>
 * {@code stance} is the setting of those numbers as {@link CombatPolicy#stance} names it and {@code has} what we
 * carried to stay alive with when it began; {@code end} is {@code win} when it stopped with the foe dead,
 * {@code death}, {@code draw} when it stopped any other way and {@code long} when it was cut at
 * {@link Rules#LONGEST}; {@code foes} is the health the foes lost while they were ours to fight, in full health bars,
 * {@code mine} what we lost, and {@code score} the first less the second; {@code least} is the lowest our health
 * stood; {@code spent} the pieces of what we carried that are gone. {@code unsynced} and {@code mixed} at the end
 * mark a fight that is written down and judged for nothing: the foe's health was not shown in it, or the kind of foe,
 * the lag band or the stance changed under it.
 */
final class CombatOutcome {
    /**
     * PROVISIONAL — PENDING IN-GAME CALIBRATION. Every number that decides what is counted, in one place. They come
     * from 4201 recorded fights on the integrated server against VexBot (2026-10-03 to 10-08) and have not been
     * checked on a remote server. None of them is read by a combat decision, so a wrong one costs a wrong tally,
     * never a wrong move. Changing one changes what a tally means: totals from before and after cannot be pooled.
     */
    static final class Rules {
        /**
         * Blocks, eye to hitbox: a tick counts only with the foe this near. It is the distance at which the process
         * stops pathing and steers the fight itself, and it held 95% of the recorded ticks of sword and shield
         * fights. Lower drops ticks spent closing after a knockback, higher adds ticks of pure chase to both rates.
         * To change it: the share of both sides' damage that falls outside, per kit, from fights on a real server.
         */
        static final double ACTIVE_DIST = CombatGeometry.DRIVE;
        /**
         * Ticks after a click of ours in which a drop of the foe's health is taken to be that click's. Recorded
         * drops followed the click by one tick in 72.0% and two in 26.6% of cases. Lower loses our own late hits,
         * higher credits us with fall, fire and third-party damage. That was with the server in our own process: a
         * server further off answers later by its round trip, and the window is that many ticks longer (the lag
         * band, as {@link CombatLag} measures it). To change it: the click-to-drop delay measured on the server in
         * question, less its band.
         */
        static final int CLICK_TICKS = 2;
        /**
         * Active ticks in a full window. Over 5109 such windows of sword and shield fights, damage dealt per tick
         * had a coefficient of variation of 0.47, so telling two values 10% apart takes about 350 windows each.
         * Shorter windows are noisier one by one and more of them are cut by a death, longer ones give fewer per
         * fight; the pooled totals do not depend on it. To change it: the same spread measured on the kit at hand.
         */
        static final int WINDOW = 200;
        /**
         * Hurt flashes in a window with no health drop at all before it is marked unsynced. No recorded fight had
         * three flashes and no drop. Lower marks windows of blocked hits, higher lets a server that hides health
         * pass as a fight in which nothing was dealt. To change it: fights on a server known to hide health.
         */
        static final int UNSYNCED_FLASHES = 3;
        /**
         * Fight ticks after which an engagement is written down as it stands and the next one begun: one that never
         * ends would never be judged. Nothing recorded says how long a real fight runs, because the bench ends a
         * round at 1800 or 3600 ticks; five minutes is longer than any of those. Lower cuts real fights in two and
         * judges each half as a draw, higher leaves a stalemate out of the count for longer.
         * To change it: the lengths of fights against players on a real server.
         */
        static final int LONGEST = 6000;

        private Rules() {}
    }

    /** One window, or the sum of the windows fought with one kit against one kind of foe under one setting. */
    static final class Tally {
        int active, wall, clicks, flashes, drops, kills, deaths, windows, unsynced;
        /** A single window's place in its engagement, the first being 0. Means nothing in a sum. */
        int seq;
        double dealt, drop, taken, out;

        /** Damage our clicks did per active tick. */
        double dealtRate() {
            return active > 0 ? dealt / active : 0;
        }

        /** Damage we took per active tick. What we took on the other fight ticks is in it too: leaving it out would flatter the value. */
        double takenRate() {
            return active > 0 ? (taken + out) / active : 0;
        }

        private void add(Tally w) {
            active += w.active;
            wall += w.wall;
            clicks += w.clicks;
            flashes += w.flashes;
            drops += w.drops;
            kills += w.kills;
            deaths += w.deaths;
            windows += w.windows;
            unsynced += w.unsynced;
            dealt += w.dealt;
            drop += w.drop;
            taken += w.taken;
            out += w.out;
        }
    }

    /**
     * One engagement, from its first active tick to its end. Health is counted in full bars of whoever's it is, so a
     * foe killed from full is 1 and a mob wave is as many as were killed.
     */
    static final class Fight {
        /** The kit, kind of foe and lag band it began in; the stance it was fought under; what we carried to stay alive with, as "apple+totem". */
        String context, stance, carried;
        /** How it stopped: "win" with the foe dead, "death", "draw" any other way, "long" cut at {@link Rules#LONGEST}. */
        String end;
        int wall, active, kills, spent;
        /** The foe's health was not shown in one of its windows; the kind of foe, the lag band or the stance changed under it. Either way it is judged for nothing. */
        boolean unsynced, mixed;
        /** What the foes lost while they were ours to fight, what we lost (what was healed back is not lost), and the lowest our health stood. */
        double foes, mine, least;

        /** How far the fight moved the two of us apart, in our favour: what the foes lost less what we did. */
        double score() {
            return foes - mine;
        }

        boolean death() {
            return "death".equals(end);
        }
    }

    /** Told of every window as it closes, after it is written down, and of every engagement as it ends. */
    interface Listener {
        void window(String context, String setting, Tally window, String end);

        default void fight(Fight fight) {}
    }

    private static final float EPS = 0.01f; // the recorder's own threshold for a change of health
    private static final int NEVER = 1 << 20;

    private final Consumer<String> sink;
    private final Listener listener;
    private final Map<String, Tally> totals = new LinkedHashMap<>();

    private String kit, context; // the kit this engagement began with, and the open window's kit and kind of foe
    private Tally window;
    private String setting;
    private int seq; // windows this engagement has closed
    private boolean seen, lastActive; // the tick before was a fight tick with readable health; it was active
    private int lastFoe, lastHurt, sinceClick = NEVER;
    private int lag; // the open window's lag band
    private float lastFoeHp, lastMyHp;
    private Fight fight; // the engagement under way, null between two
    private float myMax = 20, foeMax = 20;
    private String stance = CombatPolicy.BASE, carried = "";
    private int supplies, suppliesAlive, suppliesStart;
    private int stintFoe; // the foe whose health the stint under way is counted on
    private double stint, stintLast, myStart, myLast; // that foe's health when it became ours to fight and when last read; ours when the fight began and when last read
    private boolean down; // the foe last read was dead

    /** @param sink where a finished window's line goes */
    CombatOutcome(Consumer<String> sink) {
        this(sink, null);
    }

    /** @param listener told of every closed window, or null */
    CombatOutcome(Consumer<String> sink, Listener listener) {
        this.sink = sink;
        this.listener = listener;
    }

    /** No engagement is under way: the next tick has to bring the kit. */
    boolean idle() {
        return kit == null;
    }

    /** The kit the engagement under way began with, null when there is none. */
    String kit() {
        return kit;
    }

    /** The window now open, or the next one to open, is the first of its engagement. */
    boolean opening() {
        return kit == null || seq == 0;
    }

    /** No engagement is being counted as a whole: the numbers a fight is fought whole under may move. */
    boolean between() {
        return fight == null;
    }

    /**
     * What the ticks from here on are fought for, told before each: full health for each of us, the numbers a fight
     * is fought whole under as {@link CombatPolicy#stance} names them, what we carry to stay alive with and how many
     * pieces of it. Never told, health is counted in bars of 20 and nothing is carried.
     */
    void stakes(float myMax, float foeMax, String stance, String carried, int supplies) {
        this.myMax = myMax;
        this.foeMax = foeMax;
        this.stance = stance;
        this.carried = carried;
        this.supplies = supplies;
    }

    /** Active ticks in the window now open, 0 when there is none. */
    int open() {
        return window == null ? 0 : window.active;
    }

    /** The name windows are kept under: the kit an engagement began with and the kind of foe. */
    static String context(String kit, boolean foePlayer) {
        return kit + (foePlayer ? "/player" : "/mob");
    }

    /**
     * The same with the lag band, as "@2" for two ticks. No lag has no mark: everything counted before the band was
     * read was counted on the integrated server, and stays under the name it had.
     */
    static String context(String kit, boolean foePlayer, int lag) {
        return context(kit, foePlayer) + (lag > 0 ? "@" + lag : "");
    }

    /** A context's name without its lag band. */
    static String plain(String context) {
        int at = context.lastIndexOf('@');
        return at < 0 ? context : context.substring(0, at);
    }

    /**
     * One tick of the fight body, after its decision.
     *
     * @param foe     the target's entity id
     * @param foeHp   its health and absorption, 0 once it is dead
     * @param myHp    ours, the same way
     * @param dist    eye to hitbox
     * @param clicked we sent an attack this tick
     * @param kitNow  what we carry, read only on the tick an engagement begins
     * @param setting the tuned numbers in force, as {@link CombatPolicy#setting} names them
     */
    void tick(int foe, boolean foePlayer, float foeHp, int hurtTime, int hurtDuration, float myHp, double dist, boolean clicked, String kitNow, String setting) {
        tick(foe, foePlayer, foeHp, hurtTime, hurtDuration, myHp, dist, clicked, kitNow, setting, 0);
    }

    /**
     * @param lag the lag band this tick is fought in. A window that it changes under is closed as "lag": part of it
     *            was fought at one latency and part at another, and it is written down but judged for neither.
     */
    void tick(int foe, boolean foePlayer, float foeHp, int hurtTime, int hurtDuration, float myHp, double dist, boolean clicked, String kitNow, String setting, int lag) {
        // before the setting is looked at: another kind of foe, or another latency, may have another
        if (kit != null && window != null && !context(kit, foePlayer, lag).equals(context)) close(lag != this.lag ? "lag" : "foe");
        this.lag = lag;
        if (!setting.equals(this.setting)) { // what was read under the old setting stays with it
            close("value");
            forget();
            this.setting = setting;
        }
        boolean readable = Float.isFinite(foeHp) && Float.isFinite(myHp) && !Double.isNaN(dist);
        boolean same = seen && foe == lastFoe;
        boolean active = same && readable && lastFoeHp > 0 && lastMyHp > 0 && dist <= Rules.ACTIVE_DIST;
        boolean flash = same && hurtTime > lastHurt && hurtTime >= hurtDuration - 1;
        if (!same) sinceClick = NEVER; // a click at the last foe says nothing about this one
        if (clicked) sinceClick = 0;
        else if (sinceClick < NEVER) sinceClick++;

        if (active && kit == null) {
            kit = kitNow == null ? "?" : kitNow;
            seq = 0;
        }
        if (kit != null) {
            String ctx = context(kit, foePlayer, lag);
            if (!ctx.equals(context)) close("foe");
            if (window == null) {
                window = new Tally();
                context = ctx;
            }
            window.wall++;
            boolean alive = seen && readable && lastMyHp > 0;
            float lost = alive ? lastMyHp - Math.max(myHp, 0) : 0;
            if (lost > EPS) {
                if (active) window.taken += lost;
                else window.out += lost;
            }
            if (active) {
                window.active++;
                if (clicked) window.clicks++;
                if (flash) window.flashes++;
                float hit = lastFoeHp - Math.max(foeHp, 0);
                if (hit > EPS) {
                    window.drops++;
                    window.drop += hit;
                    if (sinceClick <= Rules.CLICK_TICKS + lag) window.dealt += hit;
                    if (foeHp <= 0) window.kills++;
                }
            }
            if (fight == null && readable) begin(ctx, seen ? lastMyHp : myHp, same ? lastFoeHp : foeHp, foe);
            if (fight != null) fought(ctx, foe, foeHp, myHp, readable, active);
            if (alive && myHp <= 0) {
                window.deaths++;
                close("death");
                kit = null;
                finish("death");
            } else if (window.active >= Rules.WINDOW) {
                close("full");
            }
            if (fight != null && fight.wall >= Rules.LONGEST) finish("long");
        }
        seen = readable;
        lastActive = active;
        lastFoe = foe;
        lastHurt = hurtTime;
        lastFoeHp = foeHp;
        lastMyHp = myHp;
    }

    /**
     * The foe was found dead before this tick's fight body ran. The process drops a dead target before it reads it,
     * so the killing blow never reaches {@link #tick}: what the foe had left when last read is that blow, one tick
     * on. It is counted under the same test as any other drop, but the tick it landed on is not: the fight body
     * runs that tick against the next foe, or not at all.
     */
    void foeDied(int foe, double dist) {
        if (fight != null && foe == stintFoe) { // dead whoever did it and however far off: that is how the fight ended
            if (stintLast > 0) fight.kills++;
            stintLast = 0;
            down = true;
        }
        if (kit == null || !seen || foe != lastFoe || lastFoeHp <= 0 || lastMyHp <= 0 || !(dist <= Rules.ACTIVE_DIST)) return;
        if (window == null) window = new Tally();
        window.drops++;
        window.kills++;
        window.drop += lastFoeHp;
        if (sinceClick < Rules.CLICK_TICKS + lag) window.dealt += lastFoeHp;
        lastFoeHp = 0;
    }

    /**
     * We died between two ticks and no tick showed it, as happens when the respawn is immediate. What we had left
     * when last read is the least the killing blow took.
     */
    void died() {
        if (kit != null && seen && lastMyHp > 0) {
            if (window == null) window = new Tally();
            if (lastActive) window.taken += lastMyHp;
            else window.out += lastMyHp;
            window.deaths++;
            close("death");
        }
        finish("death");
        kit = null;
        forget();
    }

    /** No fight body ran this tick: the engagement is over, and what it held is kept. */
    void interrupt(String why) {
        close(why);
        finish(down ? "win" : "draw");
        kit = null;
        forget();
    }

    /** Forgets the running totals. A window still open is not touched. */
    void clear() {
        totals.clear();
    }

    /** The running total for one kit and kind of foe under one setting, null if no window of it has closed. */
    Tally total(String context, String setting) {
        return totals.get(key(context, setting));
    }

    /** One line per kit, kind of foe and setting fought under so far. Closed windows only. */
    List<String> report() {
        List<String> lines = new ArrayList<>();
        totals.forEach((k, t) -> lines.add(String.format(Locale.ROOT, "%s: %d ticks, dealt %.4f/tick, taken %.4f/tick, %d windows, %d kills, %d deaths%s",
                k, t.active, t.dealtRate(), t.takenRate(), t.windows, t.kills, t.deaths, t.unsynced > 0 ? ", " + t.unsynced + " unsynced" : "")));
        return lines;
    }

    private void forget() {
        seen = lastActive = false;
        sinceClick = NEVER;
    }

    private static String key(String context, String setting) {
        return context + " " + setting;
    }

    /** Health in full bars of whoever's it is. */
    private static double share(float hp, float max) {
        return Math.max(hp, 0) / (max > 0 ? max : 20);
    }

    private void begin(String ctx, float myHp, float foeHp, int foe) {
        fight = new Fight();
        fight.context = ctx;
        fight.stance = stance;
        fight.carried = carried;
        myStart = myLast = fight.least = share(myHp, myMax);
        stint = stintLast = share(foeHp, foeMax);
        stintFoe = foe;
        suppliesStart = suppliesAlive = supplies;
        down = false;
    }

    /** One fight tick of the engagement under way. */
    private void fought(String ctx, int foe, float foeHp, float myHp, boolean readable, boolean active) {
        fight.wall++;
        if (active) fight.active++;
        if (!ctx.equals(fight.context) || !stance.equals(fight.stance)) fight.mixed = true;
        if (!readable) return;
        if (foe != stintFoe) { // another foe: what the last one lost while it was ours to fight is kept
            fight.foes += stint - stintLast;
            stint = stintLast = share(foeHp, foeMax);
            stintFoe = foe;
        }
        double now = share(foeHp, foeMax);
        if (now <= 0 && stintLast > 0) fight.kills++;
        stintLast = now;
        down = now <= 0;
        myLast = share(myHp, myMax);
        fight.least = Math.min(fight.least, myLast);
        if (myHp > 0) suppliesAlive = supplies; // what a death drops was not spent
    }

    private void finish(String end) {
        Fight f = fight;
        fight = null;
        if (f == null) return;
        f.end = end;
        f.foes += stint - stintLast;
        f.mine = myStart - (f.death() ? 0 : myLast);
        f.spent = Math.max(0, suppliesStart - suppliesAlive);
        sink.accept(String.format(Locale.ROOT, "acs-fight ctx=%s stance=%s has=%s end=%s wall=%d n=%d score=%.3f foes=%.3f mine=%.3f least=%.2f kills=%d spent=%d%s%s",
                f.context, f.stance, f.carried.isEmpty() ? "-" : f.carried, end, f.wall, f.active, f.score(), f.foes, f.mine, f.least, f.kills, f.spent,
                f.unsynced ? " unsynced" : "", f.mixed ? " mixed" : ""));
        if (listener != null) listener.fight(f);
    }

    private void close(String end) {
        Tally w = window;
        window = null;
        if (w == null || w.wall == 0 && w.deaths == 0 && w.drops == 0) return;
        w.windows = 1;
        w.seq = seq++;
        if (w.flashes >= Rules.UNSYNCED_FLASHES && w.drops == 0) w.unsynced = 1;
        if (w.unsynced > 0 && fight != null) fight.unsynced = true;
        totals.computeIfAbsent(key(context, setting), k -> new Tally()).add(w);
        sink.accept(String.format(Locale.ROOT, "acs ctx=%s set=%s n=%d wall=%d dealt=%.2f drop=%.2f taken=%.2f out=%.2f clicks=%d flashes=%d kills=%d deaths=%d seq=%d end=%s%s",
                context, setting, w.active, w.wall, w.dealt, w.drop, w.taken, w.out, w.clicks, w.flashes, w.kills, w.deaths, w.seq, end, w.unsynced > 0 ? " unsynced" : ""));
        if (listener != null) listener.window(context, setting, w, end);
    }
}
