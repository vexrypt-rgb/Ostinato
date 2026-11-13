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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Ostinato: one lease renews once per renew period however its RENEWs and GRANTs interleave. Live b12 a second
 * RENEW sent while one was unanswered stayed in flight for good on a slow link (8 renews/min per lease, not 4),
 * because each GRANT rescheduled from the older RENEW it answered.
 */
public class RenewInFlightTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Due {
        final long at;
        final LeaseSupport.Msg m;

        Due(long at, LeaseSupport.Msg m) {
            this.at = at;
            this.m = m;
        }
    }

    private final List<Due> wire = new ArrayList<>();

    /** One second of a link whose every message takes {@code oneWayMs} to arrive. */
    private void step(LeaseSupport s, long oneWayMs) {
        s.advance(1_000L);
        s.coord.tick();
        s.guard.tick();
        s.bobP.pollExecutions();
        s.aliceP.pollExecutions();
        long now = s.mono.get();
        LeaseSupport.Msg m;
        while ((m = s.queue.pollFirst()) != null) {
            wire.add(new Due(now + oneWayMs, m));
        }
        for (int i = 0; i < wire.size(); ) {
            if (wire.get(i).at <= now) {
                s.deliver(wire.remove(i).m);
                while ((m = s.queue.pollFirst()) != null) {
                    wire.add(new Due(now + oneWayMs, m));
                }
            } else {
                i++;
            }
        }
    }

    /** The one lease the guard holds, by reflection: its state is private to the guard. */
    private static Object lease(LeaseSupport s) throws Exception {
        Field f = BotLeaseGuard.class.getDeclaredField("leases");
        f.setAccessible(true);
        Map<?, ?> leases = (Map<?, ?>) f.get(s.guard);
        assertEquals(1, leases.size());
        return leases.values().iterator().next();
    }

    private static long field(Object l, String name) throws Exception {
        Field f = l.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(l);
    }

    private int renewsIn2Min(long oneWayMs, boolean secondInFlight) throws Exception {
        wire.clear();
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        s.chatLink = true; // 60 s chat lease: renew every 10 s
        LeaseSupport.leaseIdOf(s.coord.lease("Bob", "ore1", "mine diamond_ore"));
        s.pump();
        for (int i = 0; i < 20; i++) {
            step(s, oneWayMs);
        }
        if (secondInFlight) {
            // A second RENEW half a renew period after the last, while that one is still unanswered.
            Object l = lease(s);
            while (s.mono.get() - field(l, "lastReqAt") < 5_000L) {
                step(s, oneWayMs);
            }
            Field n = l.getClass().getDeclaredField("nextRenewAt");
            n.setAccessible(true);
            n.setLong(l, s.mono.get());
            s.guard.tick();
        }
        for (int i = 0; i < 30; i++) {
            step(s, oneWayMs);
        }
        int since = s.sent.size();
        for (int i = 0; i < 120; i++) {
            step(s, oneWayMs);
        }
        return s.sentSince(since, "Bob", LeaseMessage.Op.RENEW).size();
    }

    @Test
    public void twoRenewsInFlightCollapseToOneOnASlowLink() throws Exception {
        for (long oneWay : new long[]{3_000L, 4_000L}) { // RTT 7-9 s: between renewEvery/2 and renewEvery
            int n = renewsIn2Min(oneWay, true);
            assertTrue("one-way " + oneWay + " ms: " + n + " renews in 2 min, one in flight gives 12", n <= 13);
        }
    }

    @Test
    public void steadyLinkRenewsOncePerPeriod() throws Exception {
        for (long oneWay : new long[]{1_000L, 3_000L, 4_000L}) {
            int n = renewsIn2Min(oneWay, false);
            assertTrue("one-way " + oneWay + " ms: " + n + " renews in 2 min", n >= 11 && n <= 13);
        }
    }
}
