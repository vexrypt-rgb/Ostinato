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
        // Ostinato: renew every D/6 (10 s at D=60 s); resends of OFFER, REVOKE, RREQ and pending REQ stay at D/3.
        assertEquals(10_000L, LeaseClock.renewEvery(60_000L));
        assertEquals(20_000L, LeaseClock.resendEvery(60_000L));
        assertEquals(10_000L, LeaseClock.repeatGap(60_000L));
        // Chat budget: at D >= 60 s one lease costs 6 renews/min, 2 chat lines each, under the 35 lines/min cap.
        long renewsPerMin = 60_000L / LeaseClock.renewEvery(LeaseClock.CHAT_MIN_DURATION_MS);
        assertEquals(6L, renewsPerMin);
        assertTrue(2 * renewsPerMin <= 35);
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

    /** Ostinato: v2 is the compact form on the wire; v1 bodies still parse to the same message. */
    @Test
    public void v2IsCompactAndV1StillParses() {
        for (LeaseMessage.Op op : LeaseMessage.Op.values()) {
            LeaseMessage m = new LeaseMessage(op, UUID.randomUUID(), "tunnel:12,-64,-300", 7, null,
                    1_234_567_890_123L, 30_000L, "end");
            String v2 = m.encode();
            String v1 = m.encodeV1();
            assertTrue(v2, v2.startsWith("2|"));
            assertTrue(v2.length() + " vs " + v1.length(), v2.length() < v1.length() - 20);
            for (String body : new String[]{v2, v1}) {
                LeaseMessage back = LeaseMessage.parse(body);
                assertEquals(body, op, back.op);
                assertEquals(m.leaseId, back.leaseId);
                assertNull(back.commandId);
                assertEquals("tunnel:12,-64,-300", back.resource);
                assertEquals(7L, back.gen);
                assertEquals(1_234_567_890_123L, back.nonce);
                assertEquals(30_000L, back.durationMs);
                assertEquals("end", back.extra);
            }
        }
        assertNull(LeaseMessage.parse("2|z|-|r|1|-|1|1|-")); // unknown op
        assertNull(LeaseMessage.parse("2|g|short|r|1|-|1|1|-")); // id not 22 chars
        assertNull(LeaseMessage.parse("2|g|-|r|-1|-|1|1|-")); // negative gen
        assertNull(LeaseMessage.parse("2|g|-|bad resource|1|-|1|1|-"));
        assertNull(LeaseMessage.parse("2|g|-|r|1|-|1|1"));
    }
}
