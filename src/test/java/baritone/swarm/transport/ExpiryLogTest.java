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
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: a message its sender gave up on while it still waited is logged once, however many lines it took. */
public class ExpiryLogTest {

    @Test
    public void twoLineMessageExpiringIsLoggedOnce() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 1, false, 64, 0);
        List<String> log = new ArrayList<>();
        l.setLog(log::add);
        l.offer(SwarmPriority.NORMAL, "ahead", "Bob", null);
        assertEquals("ahead", l.poll(0)); // the burst is spent
        l.offer(SwarmPriority.NORMAL, "other", "Bob", null);
        SwarmRateLimiter.Deadline d = new SwarmRateLimiter.Deadline(1_000L, "command x (leased)");
        l.offer(SwarmPriority.HIGH, "cmd part 1", "Bob", d);
        l.offer(SwarmPriority.HIGH, "cmd part 2", "Bob", d);
        for (long t = 1_100L; t < 1_900L; t += 100L) {
            l.poll(t); // no token yet: both lines expire while queued
        }
        assertEquals(log.toString(), 1, log.size());
        assertTrue(log.get(0), log.get(0).startsWith("swarm: command x (leased) expired still queued for bob"));
        assertTrue(log.get(0), log.get(0).contains("2 lines"));
        assertEquals(1, l.expiredQueued());
    }

    @Test
    public void linesOfOneMessageExpiringOnDifferentPassesStillLogOnce() {
        SwarmRateLimiter l = new SwarmRateLimiter(0.5, 1, false, 64, 0);
        List<String> log = new ArrayList<>();
        l.setLog(log::add);
        l.offer(SwarmPriority.NORMAL, "ahead", "Bob", null);
        assertEquals("ahead", l.poll(0));
        SwarmRateLimiter.Deadline d = new SwarmRateLimiter.Deadline(1_000L, "command y (leased)");
        l.offer(SwarmPriority.HIGH, "cmd part 1", "Bob", d);
        l.poll(500L);
        l.offer(SwarmPriority.HIGH, "cmd part 2", "Bob", d); // same message, later expiry
        for (long t = 600L; t < 1_900L; t += 100L) {
            l.poll(t);
        }
        assertEquals(log.toString(), 1, log.size());
        assertEquals(1, l.expiredQueued());
    }
}
