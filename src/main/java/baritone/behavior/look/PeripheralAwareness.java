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

package baritone.behavior.look;

import java.util.Random;

/**
 * Ostinato: peripheral awareness for surprise threats (humanPeripheral). A threat off the center of view (outside
 * ~30 degrees) can be noticed pre-attentively before it is consciously recognized: the head makes a fast, rough,
 * partial turn toward it after a short delay (0.6..0.8 of the conscious reaction time), then the normal precise aim
 * follows once it is recognized (at the full reaction time). Seen threats are detected out to a little beyond the
 * edge of the field of view, more likely when they move, flicker or approach; past that (up to ~110 degrees, and
 * behind) only a sound or a hit gives the cue. Threats in the center skip the orienting stage: they are aimed at
 * after the normal reaction. Used only for surprise/threat looks, never for planned movement, mining or parkour.
 */
public final class PeripheralAwareness {

    /** Inside this angle from the view direction a threat is central: no separate orienting stage. */
    public static final double CENTRAL_DEG = 30;
    /** Farthest a seen (or barely on screen) threat can be; beyond, and behind, only sound or a hit cues it. */
    public static final double MAX_VISUAL_DEG = 110;

    /** What the senses get from a threat. */
    public static final class Cue {
        public final double angleOffDeg, fovHalfDeg, moving;
        public final boolean approaching, flicker, sound, hit;

        /**
         * @param angleOffDeg angle between the view direction and the threat
         * @param fovHalfDeg  horizontal half field of view
         * @param moving      0 still .. 1 fast
         * @param approaching the threat is closing in
         * @param flicker     it flickers or flashes (e.g. a hurt flash, a lit fuse)
         * @param sound       it can be heard (mob sounds, steps)
         * @param hit         it just hit us
         */
        public Cue(double angleOffDeg, double fovHalfDeg, double moving, boolean approaching, boolean flicker, boolean sound, boolean hit) {
            this.angleOffDeg = Math.abs(angleOffDeg);
            this.fovHalfDeg = fovHalfDeg;
            this.moving = Math.max(0, Math.min(1, moving));
            this.approaching = approaching;
            this.flicker = flicker;
            this.sound = sound;
            this.hit = hit;
        }
    }

    /** The response to one surprise. */
    public static final class Plan {
        /** Whether there is an early orienting turn. */
        public final boolean early;
        /** Ticks before the orienting turn starts (early), and until the threat is recognized and aimed at precisely. */
        public final int orientDelayTicks, recognizeTicks;
        /** How much of the angle the rough turn covers, and its aim error in degrees. */
        public final double fraction, roughErrorDeg;

        Plan(boolean early, int orientDelayTicks, int recognizeTicks, double fraction, double roughErrorDeg) {
            this.early = early;
            this.orientDelayTicks = orientDelayTicks;
            this.recognizeTicks = recognizeTicks;
            this.fraction = fraction;
            this.roughErrorDeg = roughErrorDeg;
        }
    }

    private PeripheralAwareness() {}

    /** Chance the threat is noticed pre-attentively, from sight (off-center), sound and hits. */
    public static double detectionProbability(Cue c) {
        double a = c.angleOffDeg;
        if (a < CENTRAL_DEG) return 0;
        double visualEdge = Math.min(MAX_VISUAL_DEG, Math.max(CENTRAL_DEG + 5, c.fovHalfDeg + 10));
        double pv = 0;
        if (a <= visualEdge) {
            // the far periphery sees less detail but is tuned to motion, flicker and looming
            pv = 0.85 - 0.35 * (a - CENTRAL_DEG) / Math.max(1, visualEdge - CENTRAL_DEG);
            if (a > c.fovHalfDeg) pv *= 0.5; // barely on screen
            pv += 0.25 * c.moving + (c.approaching ? 0.12 : 0) + (c.flicker ? 0.1 : 0);
            pv = Math.min(0.98, pv);
        }
        double ps = c.sound ? (c.approaching ? 0.9 : 0.8) : 0;
        double ph = c.hit ? 1 : 0;
        return 1 - (1 - pv) * (1 - ps) * (1 - ph);
    }

    /**
     * The response to a surprise whose conscious reaction time is {@code reactionMs}: an early orienting turn when
     * the threat is noticed in the periphery, otherwise just the normal reaction.
     */
    public static Plan plan(boolean enabled, Cue c, double reactionMs, Random rng) {
        int recognize = Math.max(1, (int) Math.round(reactionMs / 50.0));
        if (!enabled || rng.nextDouble() >= detectionProbability(c)) return new Plan(false, recognize, recognize, 0, 0);
        double preMs = reactionMs * (0.6 + 0.2 * rng.nextDouble());
        int pre = Math.max(1, (int) Math.round(preMs / 50.0));
        if (pre >= recognize) recognize = pre + 1;
        double fraction = 0.45 + 0.3 * rng.nextDouble();
        double err = 6 * rng.nextGaussian();
        return new Plan(true, pre, recognize, fraction, err);
    }
}
