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

/** Ostinato: the surprise reaction delay holds reactive looks only, for a sampled time, and never planned ones. */
public class ReactionGateTest {

    @Test
    public void surpriseHoldsAReactiveLookForTheSampledTime() throws Exception {
        ReactionModel m = HumanMouseModelTest.example().reactionModel();
        ReactionGate g = new ReactionGate();
        int ticks = g.surprise(true, m, ReactionModel.Energy.TIRED, 0, 0, new Random(1));
        assertTrue(ticks >= 4 && ticks <= 8); // 190..420 ms
        for (int i = 0; i < ticks; i++) assertTrue(g.hold(true));
        assertFalse(g.hold(true));
        assertFalse(g.holding());
    }

    @Test
    public void disabledSettingNeverDelays() {
        ReactionGate g = new ReactionGate();
        assertEquals(0, g.surprise(false, ReactionModel.defaults(), ReactionModel.Energy.TIRED, 0, 0, new Random(2)));
        assertFalse(g.hold(true));
    }

    @Test
    public void plannedLooksAreNeverDelayedAndCancelTheHold() {
        ReactionGate g = new ReactionGate();
        assertTrue(g.surprise(true, ReactionModel.defaults(), ReactionModel.Energy.TIRED, 0, 0, new Random(3)) > 0);
        // a path-following / mining / parkour look arrives: it moves at once, and the hold is gone
        assertFalse(g.hold(false));
        assertFalse(g.holding());
        assertFalse(g.hold(true));
    }
}
