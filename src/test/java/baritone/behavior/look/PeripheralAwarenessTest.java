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

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/** Ostinato: peripheral awareness gives off-center threats an early rough turn; behind needs a sound or a hit. */
public class PeripheralAwarenessTest {

    static final double FOV_HALF = 51; // fov 70 at 16:9

    static PeripheralAwareness.Cue cue(double angle, double moving, boolean approaching, boolean sound, boolean hit) {
        return new PeripheralAwareness.Cue(angle, FOV_HALF, moving, approaching, false, sound, hit);
    }

    @Test
    public void centralThreatsHaveNoOrientingStage() {
        Random r = new Random(1);
        for (int i = 0; i < 500; i++) {
            PeripheralAwareness.Plan p = PeripheralAwareness.plan(true, cue(15, 1, true, true, true), 280, r);
            assertFalse(p.early);
            assertEquals(6, p.recognizeTicks);
        }
    }

    @Test
    public void peripheralThreatsGetAnEarlyRoughTurn() {
        Random r = new Random(2);
        int early = 0;
        for (int i = 0; i < 2000; i++) {
            PeripheralAwareness.Plan p = PeripheralAwareness.plan(true, cue(45, 0.8, true, false, false), 300, r);
            if (!p.early) continue;
            early++;
            // the orienting turn starts at 0.6..0.8 of the reaction, before recognition, and covers part of the angle
            assertTrue(p.orientDelayTicks >= 3 && p.orientDelayTicks <= 5);
            assertTrue(p.orientDelayTicks < p.recognizeTicks);
            assertTrue(p.fraction >= 0.45 && p.fraction <= 0.75);
        }
        assertTrue("early " + early, early > 1700);
        // disabled: never early
        assertFalse(PeripheralAwareness.plan(false, cue(45, 1, true, true, true), 300, r).early);
    }

    @Test
    public void motionAndApproachRaiseDetection() {
        double still = PeripheralAwareness.detectionProbability(cue(48, 0, false, false, false));
        double moving = PeripheralAwareness.detectionProbability(cue(48, 1, false, false, false));
        double closing = PeripheralAwareness.detectionProbability(cue(48, 1, true, false, false));
        assertTrue(still < moving && moving < closing);
        // barely on screen is harder than well inside the view
        assertTrue(PeripheralAwareness.detectionProbability(cue(58, 0, false, false, false)) < still);
        double flick = PeripheralAwareness.detectionProbability(new PeripheralAwareness.Cue(48, FOV_HALF, 0, false, true, false, false));
        assertTrue(flick > still);
    }

    @Test
    public void behindThreatsNeedASoundOrAHit() {
        assertEquals(0, PeripheralAwareness.detectionProbability(cue(150, 1, true, false, false)), 0);
        assertEquals(0, PeripheralAwareness.detectionProbability(cue(95, 1, true, false, false)), 0); // off screen, silent
        assertTrue(PeripheralAwareness.detectionProbability(cue(150, 0, false, true, false)) >= 0.8);
        assertEquals(1, PeripheralAwareness.detectionProbability(cue(170, 0, false, false, true)), 0);
        Random r = new Random(3);
        for (int i = 0; i < 300; i++) assertFalse(PeripheralAwareness.plan(true, cue(160, 1, true, false, false), 300, r).early);
        assertTrue(PeripheralAwareness.plan(true, cue(160, 0, false, false, true), 300, r).early);
    }

    /** Ticks until the view is within 3 degrees of a threat {@code angle} off, turning at most 30 deg/tick. */
    static int ticksToAim(ReactionGate g, double angle) {
        double view = 0, orientGoal = Double.NaN;
        for (int t = 1; t < 100; t++) {
            ReactionGate.Phase ph = g.step(true);
            double goal = angle;
            if (ph == ReactionGate.Phase.HOLD) continue;
            if (ph == ReactionGate.Phase.ORIENT) {
                if (Double.isNaN(orientGoal)) orientGoal = view + (angle - view) * g.orientFraction() + g.orientErrorDeg();
                goal = orientGoal;
            }
            view += Math.max(-30, Math.min(30, goal - view));
            if (ph == ReactionGate.Phase.MOVE && Math.abs(angle - view) <= 3) return t;
        }
        return 100;
    }

    @Test
    public void peripheralThreatsAreAimedFasterThanUnnoticedOnes() {
        ReactionModel rm = ReactionModel.defaults();
        Random r = new Random(4);
        double sumPeripheral = 0, sumUnnoticed = 0;
        int n = 3000;
        for (int i = 0; i < n; i++) {
            double rt = rm.sample(ReactionModel.Energy.TIRED, i * 30.0, 0, r);
            ReactionGate a = new ReactionGate(), b = new ReactionGate();
            a.start(PeripheralAwareness.plan(true, cue(100, 0.5, true, true, false), rt, r));
            b.start(PeripheralAwareness.plan(false, cue(100, 0.5, true, true, false), rt, r));
            sumPeripheral += ticksToAim(a, 100);
            sumUnnoticed += ticksToAim(b, 100);
        }
        assertTrue("peripheral " + sumPeripheral / n + " vs " + sumUnnoticed / n, sumPeripheral / n < sumUnnoticed / n - 0.5);
    }

    @Test
    public void plannedLooksCancelTheOrientingTurn() {
        ReactionGate g = new ReactionGate();
        g.start(PeripheralAwareness.plan(true, cue(60, 1, true, false, true), 300, new Random(5)));
        assertTrue(g.holding());
        assertEquals(ReactionGate.Phase.MOVE, g.step(false)); // mining / path / parkour look
        assertFalse(g.holding());
        assertEquals(ReactionGate.Phase.MOVE, g.step(true));
    }
}
