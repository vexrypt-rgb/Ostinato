/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import baritone.scherzo.AttackFeasibility.Verdict;

import java.util.Locale;

/**
 * Each jab sent against what followed it: the verdict it went out under, and whether the opponent flashed hurt
 * within {@link #WAIT} ticks. A flash is an observed effect, not proof the jab caused it (fire, a teammate or a
 * fall can flash it too); a jab with no flash in the wait is counted missed. Game-free and bounded.
 */
public final class JabAudit {
    /** Ticks a jab waits for its flash: the same wait {@code CombatLag} gives a click. */
    public static final int WAIT = 12;

    private static final int V = Verdict.values().length;
    final long[] sent = new long[V], landed = new long[V], bandSent = new long[V], bandLanded = new long[V];
    long led, ledLanded, held;
    private long pendingTick = Long.MIN_VALUE, heldTick = Long.MIN_VALUE;
    private Verdict pendingServer, pendingBand;
    private boolean pendingLed;
    private int lastHurt;

    /** A jab went out on {@code tick}. {@code clientRay} false is a jab the client's own ray would not have sent. */
    public void sent(long tick, Verdict server, Verdict band, boolean clientRay, int hurtTime) {
        settle(false);
        pendingTick = tick;
        pendingServer = server;
        pendingBand = band;
        pendingLed = !clientRay;
        lastHurt = hurtTime;
    }

    /** A jab held back on {@code tick}; counted once a tick however often it was asked. */
    public void held(long tick) {
        if (tick == heldTick) return;
        heldTick = tick;
        held++;
    }

    /** The opponent's hurt flash this tick. */
    public void tick(long tick, int hurtTime, int hurtDuration) {
        if (pendingTick == Long.MIN_VALUE) return;
        long since = tick - pendingTick;
        boolean flash = hurtTime > lastHurt && hurtTime >= hurtDuration - 1;
        lastHurt = hurtTime;
        if (since >= 1 && since <= WAIT && flash) settle(true);
        else if (since > WAIT) settle(false);
    }

    /** The opponent changed or the fight ended: a pending jab is counted on what was seen. */
    public void abandon() {
        settle(false);
    }

    private void settle(boolean hit) {
        if (pendingTick == Long.MIN_VALUE) return;
        sent[pendingServer.ordinal()]++;
        bandSent[pendingBand.ordinal()]++;
        if (hit) {
            landed[pendingServer.ordinal()]++;
            bandLanded[pendingBand.ordinal()]++;
        }
        if (pendingLed) {
            led++;
            if (hit) ledLanded++;
        }
        pendingTick = Long.MIN_VALUE;
    }

    public long total() {
        long n = 0;
        for (long s : sent) n += s;
        return n;
    }

    public void clear() {
        java.util.Arrays.fill(sent, 0);
        java.util.Arrays.fill(landed, 0);
        java.util.Arrays.fill(bandSent, 0);
        java.util.Arrays.fill(bandLanded, 0);
        led = ledLanded = held = 0;
        pendingTick = heldTick = Long.MIN_VALUE;
    }

    /** "landed/sent" per verdict, for the full server ray and for the band; empty with no jab and none held. */
    public String report(String label) {
        if (total() == 0 && held == 0) return "";
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "scherzo jab %s sent=%d ray", label, total()));
        for (Verdict v : Verdict.values()) b.append(' ').append(v.name().toLowerCase(Locale.ROOT)).append('=').append(landed[v.ordinal()]).append('/').append(sent[v.ordinal()]);
        b.append(" | band");
        for (Verdict v : Verdict.values()) b.append(' ').append(v.name().toLowerCase(Locale.ROOT)).append('=').append(bandLanded[v.ordinal()]).append('/').append(bandSent[v.ordinal()]);
        b.append(String.format(Locale.ROOT, " | led=%d/%d held=%d", ledLanded, led, held));
        return b.toString();
    }
}
