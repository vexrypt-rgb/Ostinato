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


package baritone.swarm.transport;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SwarmRateLimiterTest {

    private static List<String> drain(SwarmRateLimiter l, long now) {
        List<String> out = new ArrayList<>();
        for (String s; (s = l.poll(now)) != null; ) {
            out.add(s);
        }
        return out;
    }

    @Test
    public void burstThenNothingUntilRefill() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 5, false, 64, 0);
        for (int i = 0; i < 20; i++) {
            assertTrue(l.offer(SwarmPriority.NORMAL, "m" + i));
        }
        assertEquals(Arrays.asList("m0", "m1", "m2", "m3", "m4"), drain(l, 0));
        assertNull(l.poll(1999)); // 0.5/s: next token at 2000 ms
        assertEquals("m5", l.poll(2000));
        assertNull(l.poll(2001));
        assertEquals(6, l.sent());
        assertEquals(14, l.queued());
    }

    @Test
    public void sustainedRateMatchesSetting() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 5, false, 1000, 0);
        for (int i = 0; i < 500; i++) {
            l.offer(SwarmPriority.NORMAL, "m" + i);
        }
        int sent = 0;
        for (long t = 0; t <= 600_000; t += 50) {
            sent += drain(l, t).size();
        }
        // burst 5 + 0.5/s * 600 s
        assertEquals(5 + 300, sent);
    }

    @Test
    public void idleTimeDoesNotBankMoreThanBurst() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 3, false, 64, 0);
        for (int i = 0; i < 10; i++) {
            l.offer(SwarmPriority.NORMAL, "m" + i);
        }
        assertEquals(3, drain(l, 3_600_000).size());
    }

    @Test
    public void highPriorityGoesFirstFifoWithinPriority() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 1, false, 64, 0);
        l.offer(SwarmPriority.LOW, "low1");
        l.offer(SwarmPriority.NORMAL, "norm1");
        l.offer(SwarmPriority.HIGH, "high1");
        l.offer(SwarmPriority.LOW, "low2");
        l.offer(SwarmPriority.HIGH, "high2");
        l.offer(SwarmPriority.NORMAL, "norm2");
        List<String> order = new ArrayList<>();
        for (long t = 0; order.size() < 6; t += 2000) {
            order.addAll(drain(l, t));
        }
        assertEquals(Arrays.asList("high1", "high2", "norm1", "norm2", "low1", "low2"), order);
    }

    @Test
    public void lateHighPriorityOvertakesQueuedLow() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 1, false, 64, 0);
        l.offer(SwarmPriority.LOW, "low1");
        l.offer(SwarmPriority.LOW, "low2");
        assertEquals("low1", l.poll(0));
        l.offer(SwarmPriority.HIGH, "pong");
        assertEquals("pong", l.poll(2000));
        assertEquals("low2", l.poll(4000));
    }

    @Test
    public void clampedUnderVanillaUnlessUnsafe() {
        SwarmRateLimiter l = new SwarmRateLimiter(5.0, 50, false, 64, 0);
        assertEquals(SwarmRateLimiter.SAFE_MAX_RATE, l.rate(), 0);
        assertEquals(SwarmRateLimiter.SAFE_MAX_BURST, l.burst());
        SwarmRateLimiter u = new SwarmRateLimiter(5.0, 50, true, 64, 0);
        assertEquals(5.0, u.rate(), 0);
        assertEquals(50, u.burst());
        SwarmRateLimiter z = new SwarmRateLimiter(0, 0, false, 0, 0);
        assertEquals(SwarmRateLimiter.MIN_RATE, z.rate(), 0);
        assertEquals(1, z.burst());
        assertEquals(SwarmRateLimiter.MIN_RATE, new SwarmRateLimiter(Double.NaN, 1, false, 1, 0).rate(), 0);
    }

    @Test
    public void fullQueueDropsLowerPriorityFirst() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 1, false, 3, 0);
        assertTrue(l.offer(SwarmPriority.LOW, "low1"));
        assertTrue(l.offer(SwarmPriority.LOW, "low2"));
        assertTrue(l.offer(SwarmPriority.NORMAL, "norm1"));
        assertTrue(l.offer(SwarmPriority.HIGH, "high1")); // evicts newest LOW (low2)
        assertFalse(l.offer(SwarmPriority.LOW, "low3")); // nothing lower to evict
        assertEquals(2, l.dropped());
        List<String> order = new ArrayList<>();
        for (long t = 0; t < 10_000; t += 2000) {
            order.addAll(drain(l, t));
        }
        assertEquals(Arrays.asList("high1", "norm1", "low1"), order);
    }

    // Ostinato: duplicate acceptance must neither consume capacity nor count as a drop.
    @Test
    public void duplicateAtCapacityAndLateCancel() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 1, false, 32, 0);
        for (int i = 0; i < 32; i++) {
            assertTrue(l.offer(SwarmPriority.LOW, "retry" + i));
        }
        assertTrue(l.offer(SwarmPriority.LOW, "retry0"));
        assertEquals(32, l.queued());
        assertEquals(0, l.dropped());
        assertEquals("retry0", l.poll(0));
        assertTrue(l.offer(SwarmPriority.HIGH, "cancel"));
        assertEquals("cancel", l.poll(2000));
        assertEquals("retry1", l.poll(4000));
        assertEquals(30, l.queued());
    }

    /**
     * Replays vanilla 1.16.1 ServerGamePacketListenerImpl: +20 per chat packet, kick when the
     * counter is above 200, -1 per server tick. The client sends everything the limiter allows.
     *
     * @return highest counter value seen
     */
    private static int vanillaPeak(SwarmRateLimiter l, long msPerServerTick, long seconds) {
        int counter = 0;
        int peak = 0;
        int sequence = 0; // Ostinato: distinct traffic keeps the spam simulation saturated.
        long nextTick = msPerServerTick;
        for (long t = 0; t <= seconds * 1000; t += 10) {
            while (l.queued() < 32) {
                l.offer(SwarmPriority.NORMAL, "x" + sequence++);
            }
            for (String s; (s = l.poll(t)) != null; ) {
                counter += 20;
                peak = Math.max(peak, counter);
            }
            while (t >= nextTick) {
                if (counter > 0) {
                    counter--;
                }
                nextTick += msPerServerTick;
            }
        }
        return peak;
    }

    @Test
    public void clampedMaximumNeverTripsVanillaSpamKickAt20Tps() {
        SwarmRateLimiter l = new SwarmRateLimiter(100, 100, false, 64, 0);
        int peak = vanillaPeak(l, 50, 600);
        assertTrue("peak " + peak, peak <= 200);
    }

    @Test
    public void defaultsStaySafeAt10Tps() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 5, false, 64, 0);
        int peak = vanillaPeak(l, 100, 600);
        assertTrue("peak " + peak, peak <= 200);
    }

    @Test
    public void unsafeSettingsDoTripIt() {
        assertTrue(vanillaPeak(new SwarmRateLimiter(0.5, 11, true, 64, 0), 50, 5) > 200);
        assertTrue(vanillaPeak(new SwarmRateLimiter(1.2, 5, true, 64, 0), 50, 600) > 200);
    }

    // Ostinato: link monitor figures — lines sent in the last minute against the learned cap.
    @Test
    public void windowCountsSentLinesAgainstTheCap() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 5, false, 64, 0);
        assertEquals(35, l.windowCap()); // 30 a minute from the rate plus the burst of 5
        assertEquals(0, l.sentInWindow(0));
        for (int i = 0; i < 8; i++) {
            l.offer(SwarmPriority.NORMAL, "m" + i);
        }
        assertEquals(5, drain(l, 0).size());
        assertEquals(5, l.sentInWindow(0)); // only polled lines count, not queued ones
        assertEquals("m5", l.poll(2_000));
        assertEquals(6, l.sentInWindow(2_000));
        assertEquals(1, l.sentInWindow(60_000)); // the burst at 0 has left the window
        assertEquals(0, l.sentInWindow(62_000));
    }
}
