/*
 * Ostinato: new file (spectator learning). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: running summary statistics of how one player fights, fed from what the spectator saw and decoded. Only
 * numbers are kept (counts, sums, last-event stamps), never the tick stream, so a watched player costs a few hundred
 * bytes however long they are watched. One instance per player for their lifetime, and one per side of each fight.
 * Not thread-safe: the spectator's worker owns it; {@link #traits} copies out.
 */
public final class Habits {
    // movement, over decoded intervals near a foe
    int combatTicks, strafeTicks, forwardTicks, backTicks, sprintTicks, sneakTicks, jumpEvents, intervals;
    int strafeSwitches, lastStrafe, strafeRunStart = -1;
    double strafeRunSum;
    int strafeRuns;
    int wtaps, wReleaseTick = -100;
    boolean wasForwardSprint;
    double confidenceSum;
    int retreatLowTicks, lowTicks;
    // swings and hits
    int swings, swingsInReach, hits, crits, lastSwing = -1000, gaps, hurtTaken;
    double gapSum, gapSq, aimYawSum, aimPitchSum;
    int aimN;
    int comboLen, comboMax, combos, comboSum;
    // items
    int eats, gapples, pearls, totems, pots;
    double eatHpSum, pearlHpSum, potHpSum;
    int eatHpN, pearlHpN, potHpN;
    int firstTick = -1, lastTick;

    void seen(int tick) {
        if (firstTick < 0) firstTick = tick;
        lastTick = tick;
    }

    /**
     * One decoded interval. {@code foeDist} below 0 means no foe near; radial is the speed towards the foe (+ closing),
     * {@code hp} the estimated health or NaN.
     */
    void decoded(int tick, InputDecoder.Decoded d, double foeDist, double radial, float hp) {
        seen(tick);
        if (d.reset) return;
        intervals++;
        confidenceSum += d.confidence;
        if (foeDist < 0 || foeDist > 8) return;
        int t = d.ticks;
        combatTicks += t;
        if (d.strafe != 0) strafeTicks += t;
        if (d.forward > 0) forwardTicks += t;
        if (d.forward < 0) backTicks += t;
        if (d.sprint) sprintTicks += t;
        if (d.sneak) sneakTicks += t;
        if (d.jump) jumpEvents++;
        if (d.strafe != 0 && lastStrafe != 0 && d.strafe != lastStrafe) {
            strafeSwitches++;
            if (strafeRunStart >= 0) {
                strafeRunSum += tick - strafeRunStart;
                strafeRuns++;
            }
            strafeRunStart = tick;
        } else if (d.strafe != 0 && lastStrafe == 0) {
            strafeRunStart = tick;
        }
        if (d.strafe != 0) lastStrafe = d.strafe;
        // a W-tap: forward let go for at most 5 ticks while sprinting at someone, then pressed again
        boolean fwdSprint = d.forward > 0 && d.sprint;
        if (wasForwardSprint && d.forward <= 0) wReleaseTick = tick;
        if (fwdSprint && !wasForwardSprint && tick - wReleaseTick <= 5 && foeDist < 4.5) wtaps++;
        wasForwardSprint = fwdSprint;
        if (!Float.isNaN(hp) && hp <= 8) {
            lowTicks += t;
            if (radial < -0.05) retreatLowTicks += t;
        }
    }

    void swing(int tick, double yawErr, double pitchErr, boolean inReach) {
        seen(tick);
        swings++;
        if (inReach) swingsInReach++;
        int gap = tick - lastSwing;
        if (gap > 0 && gap < 60) {
            gaps++;
            gapSum += gap;
            gapSq += (double) gap * gap;
        }
        lastSwing = tick;
        if (!Double.isNaN(yawErr)) {
            aimYawSum += Math.abs(yawErr);
            aimPitchSum += Math.abs(pitchErr);
            aimN++;
        }
    }

    /** We (this player) landed a hit; {@code crit} from the crit particles the server sent with it. */
    void hit(int tick, boolean crit) {
        seen(tick);
        hits++;
        if (crit) crits++;
        comboLen++;
        comboMax = Math.max(comboMax, comboLen);
    }

    /** This player was hit by the foe: whatever combo they had is over. */
    void hurt(int tick) {
        seen(tick);
        hurtTaken++;
        endCombo();
    }

    private void endCombo() {
        if (comboLen >= 2) {
            combos++;
            comboSum += comboLen;
        }
        comboLen = 0;
    }

    void ate(int tick, boolean gapple, float hp) {
        seen(tick);
        eats++;
        if (gapple) gapples++;
        if (!Float.isNaN(hp)) {
            eatHpSum += hp;
            eatHpN++;
        }
    }

    void pearl(int tick, float hp) {
        seen(tick);
        pearls++;
        if (!Float.isNaN(hp)) {
            pearlHpSum += hp;
            pearlHpN++;
        }
    }

    void pot(int tick, float hp) {
        seen(tick);
        pots++;
        if (!Float.isNaN(hp)) {
            potHpSum += hp;
            potHpN++;
        }
    }

    void totem(int tick) {
        seen(tick);
        totems++;
    }

    double cps() {
        double secs = Math.max(1, combatTicks) / 20.0;
        return swingsInReach / secs;
    }

    /** Named numbers that compare across players; NaN where there was nothing to measure. */
    public Map<String, Double> traits() {
        Map<String, Double> m = new LinkedHashMap<>();
        double ct = combatTicks;
        m.put("strafeShare", ct > 0 ? strafeTicks / ct : Double.NaN);
        m.put("strafeRun", strafeRuns > 0 ? strafeRunSum / strafeRuns : Double.NaN);
        m.put("forwardShare", ct > 0 ? forwardTicks / ct : Double.NaN);
        m.put("backShare", ct > 0 ? backTicks / ct : Double.NaN);
        m.put("sprintShare", ct > 0 ? sprintTicks / ct : Double.NaN);
        m.put("jumpsPerSec", ct > 0 ? jumpEvents / (ct / 20) : Double.NaN);
        m.put("wtapsPerHit", hits > 0 ? (double) wtaps / hits : Double.NaN);
        m.put("cps", ct > 0 ? cps() : Double.NaN);
        double gm = gaps > 0 ? gapSum / gaps : Double.NaN;
        m.put("swingGap", gm);
        m.put("swingGapSd", gaps > 1 ? Math.sqrt(Math.max(0, gapSq / gaps - gm * gm)) : Double.NaN);
        m.put("aimYawErr", aimN > 0 ? aimYawSum / aimN : Double.NaN);
        m.put("hitRate", swings > 0 ? (double) hits / swings : Double.NaN);
        m.put("critShare", hits > 0 ? (double) crits / hits : Double.NaN);
        m.put("comboMax", hits > 0 ? (double) Math.max(comboMax, comboLen) : Double.NaN);
        m.put("comboMean", combos > 0 ? (double) comboSum / combos : Double.NaN);
        m.put("eatHp", eatHpN > 0 ? eatHpSum / eatHpN : Double.NaN);
        m.put("eats", (double) eats);
        m.put("pearls", (double) pearls);
        m.put("pearlHp", pearlHpN > 0 ? pearlHpSum / pearlHpN : Double.NaN);
        m.put("potHp", potHpN > 0 ? potHpSum / potHpN : Double.NaN);
        m.put("retreatWhenLow", lowTicks > 0 ? (double) retreatLowTicks / lowTicks : Double.NaN);
        m.put("decodeConfidence", intervals > 0 ? confidenceSum / intervals : Double.NaN);
        return m;
    }

    /** One line, for logs and the {@code #spectate} command. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : traits().entrySet()) {
            if (e.getValue().isNaN()) continue;
            sb.append(sb.length() > 0 ? " " : "").append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.2f", e.getValue()));
        }
        return sb.toString();
    }

    int swings() {
        return swings;
    }

    int combatTicks() {
        return combatTicks;
    }
}
