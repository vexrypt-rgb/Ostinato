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

package baritone.process;

import java.util.Locale;

/**
 * What the Autotune Combat System is doing for the kit and kind of foe at hand, as plain values for the HUD card. A
 * copy made for show: nothing reads it back, and no decision looks at it.
 */
public final class AcsStatus {

    public enum Phase {
        /** Autotune is off: the baseline is in force and the fights are only counted. */
        OFF,
        /** On, fighting under the trusted value until the next trial opens. */
        WATCHING,
        /** On, fighting in turns under the trusted value and one neighbour. */
        TRIAL
    }

    /** A fight is under way. */
    public boolean fighting;
    public Phase phase = Phase.OFF;
    /** The kit and kind of foe, the name of the number taken up, and the last verdict in a few words ("" before the first). */
    public String context = "", name = "", last = "";
    /** The lag band the fight is in: whole ticks of round trip to the server, 0 with none to speak of. */
    public int lag;
    /** Which number is taken up (-1 when the kit has a use for none), its place among those the kit has a use for, and how many they are. */
    public int knob = -1, at, knobs;
    /** The number's baseline, bounds and step, in whole parts of {@code 1/per} of its own unit. */
    public int base, min, max, step, per = 1;
    /** The value in force this tick, the one trusted here, and the one on trial (the trusted one when none is). */
    public int value, trusted, candidate;
    /** Windows left before the next trial opens, and how many the wait began with. */
    public int rest, restOf;
    /** The look coming up, from 1, out of how many. */
    public int look, looks;
    /** Windows' worth of fighting the shorter side of the trial has had, and what the look coming up asks of each. */
    public double fought, needed;
    /** The candidate against the trusted value: per cent and standard errors. */
    public double dealtPct, takenPct, zDealt, zTaken;
    public int kept, dropped, reverted;
    /** Active ticks in the window now open, out of how many. */
    public int windowTicks, windowSize;
    /** Closed windows under the value in force, and what they held per active tick. */
    public int windows;
    public double dealtRate, takenRate;
    /** The numbers a fight is fought whole under: one of them is on trial, its name, the value trusted and the one tried. */
    public boolean wholeTrial;
    public String wholeName = "", wholeTrusted = "", wholeCandidate = "";
    /** Fights the shorter side of that trial has had and what the look coming up asks of each; fights left before the next trial opens. */
    public int wholeFought, wholeNeeded, wholeRest;
    public int wholeKept, wholeDropped, wholeReverted;

    /** The values the tuner can stand on, lowest first: the baseline and whole steps from it inside the bounds. */
    public int[] stops() {
        if (step <= 0 || min > base || max < base) return new int[]{base};
        int below = (base - min) / step, above = (max - base) / step;
        int[] out = new int[below + above + 1];
        for (int i = 0; i < out.length; i++) out[i] = base + (i - below) * step;
        return out;
    }

    /** A value of the number taken up in its own unit: 10, 0.86, 4.5. */
    public String show(int v) {
        return per == 1 ? Integer.toString(v) : String.format(Locale.ROOT, per == 10 ? "%.1f" : "%.2f", v / (double) per);
    }

    /** The kit and kind of foe as the card names them, with the lag band in milliseconds when there is one. */
    public String kit() {
        return CombatOutcome.plain(context).replace("/player", "").replace("/mob", " (mobs)") + (lag > 0 ? "  ~" + lag * 50 + "ms" : "");
    }

    /** The number's name without its group, and its place among the numbers tuned for this kit when there are more. */
    public String title() {
        String shortName = name.substring(name.lastIndexOf('.') + 1);
        return knobs > 1 ? shortName + "  " + at + "/" + knobs : shortName;
    }

    /** How far along the wait or the look is, 0 to 1. */
    public float progress() {
        double p = phase == Phase.TRIAL ? (needed > 0 ? fought / needed : 0)
                : phase == Phase.WATCHING ? (restOf > 0 ? 1 - (double) rest / restOf : 0) : 0;
        return (float) Math.max(0, Math.min(1, p));
    }

    /** One short line on what is being waited for. */
    public String caption() {
        switch (phase) {
            case TRIAL:
                return String.format(Locale.ROOT, "look %d/%d  %.1f of %.0f windows", look, looks, Math.min(fought, needed), needed);
            case WATCHING:
                if (knob < 0) return "nothing to tune for this kit";
                return "next trial in " + rest + (rest == 1 ? " window" : " windows");
            default:
                return "off: baseline in force";
        }
    }

    /** One short line on the numbers a fight is fought whole under, "" while autotune is off. */
    public String whole() {
        if (phase == Phase.OFF) return "";
        if (wholeTrial) return "per fight: " + wholeName + " " + wholeCandidate + " vs " + wholeTrusted + "  " + Math.min(wholeFought, wholeNeeded) + "/" + wholeNeeded;
        return "per fight: next trial in " + wholeRest + (wholeRest == 1 ? " fight" : " fights");
    }

    /** Kept, dropped and taken back, in a few characters. */
    public String score() {
        int back = reverted + wholeReverted;
        return (kept + wholeKept) + " kept  " + (dropped + wholeDropped) + " dropped" + (back > 0 ? "  " + back + " back" : "");
    }
}
