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

package baritone.swarm.lease;

import org.junit.After;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Ostinato: Phase 2a clock, rate meter, fence and wire format. */
public class LeaseClockRateFenceTest {

    @After
    public void clearFence() {
        LeaseFence.clear();
    }

    @Test
    public void clockBounds() {
        assertEquals(1_000L + 30_000L, LeaseClock.botAuthUntil(1_000L, 30_000L));
        assertEquals(1_000L + 30_000L + 3_000L, LeaseClock.coordDeadline(1_000L, 30_000L, 3_000L));
        assertEquals(10L + 30_000L + 3_000L + 500L, LeaseClock.notBeforeWall(10L, 30_000L, 3_000L, 500L));
        assertEquals(LeaseClock.CHAT_MIN_DURATION_MS, LeaseClock.effectiveDuration(30_000L, true));
        assertEquals(LeaseClock.MIN_DURATION_MS, LeaseClock.effectiveDuration(1_000L, false));
        assertEquals(20_000L, LeaseClock.renewEvery(60_000L));
        // Chat budget: with D >= 60s and renew at D/3 a bot sends <= 3 renews/min.
        assertTrue(60_000L / LeaseClock.renewEvery(LeaseClock.CHAT_MIN_DURATION_MS) <= 35);
    }

    @Test
    public void rateWindow() {
        AtomicLong t = new AtomicLong(0);
        LeaseRate r = new LeaseRate(t::get);
        for (int i = 0; i < 5; i++) {
            r.sent("Bob");
        }
        r.received("bob");
        assertEquals(5, r.sentPerMin("Bob"));
        assertEquals(1, r.receivedPerMin("Bob"));
        assertTrue(r.describe().toString(), r.describe().toString().contains("budget=35"));
        t.addAndGet(LeaseRate.WINDOW_MS + 1);
        assertEquals(0, r.sentPerMin("Bob"));
    }

    @Test
    public void fenceOffByDefault() {
        LeaseFence.clear();
        assertFalse(LeaseFence.denied());
        long before = LeaseFence.denials();
        LeaseFence.install(() -> true);
        assertTrue(LeaseFence.denied());
        assertTrue(LeaseFence.denials() > before);
        LeaseFence.clear();
        assertFalse(LeaseFence.denied());
    }

    @Test
    public void wireRoundTrip() {
        LeaseMessage m = new LeaseMessage(LeaseMessage.Op.GRANT, UUID.randomUUID(), "ore1", 4, UUID.randomUUID(),
                99L, 60_000L, "-");
        LeaseMessage back = LeaseMessage.parse(m.encode());
        assertEquals(m.op, back.op);
        assertEquals(m.leaseId, back.leaseId);
        assertEquals(4L, back.gen);
        assertEquals(99L, back.nonce);
        assertTrue(LeaseMessage.Op.GRANT.fromCoordinator());
        assertFalse(LeaseMessage.Op.REQ.fromCoordinator());
        assertNull(LeaseMessage.parse("garbage"));
    }
}
