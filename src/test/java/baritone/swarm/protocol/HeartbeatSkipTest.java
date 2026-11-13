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

package baritone.swarm.protocol;

import baritone.swarm.SwarmConfig;
import baritone.swarm.SwarmControl;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.transport.InMemorySwarmBus;
import baritone.swarm.transport.SwarmPriority;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Ostinato: any frame a peer got from us counts as liveness, so it gets no heartbeat in that interval. The peer must
 * still never see us go quiet for longer than a heartbeat interval.
 */
public class HeartbeatSkipTest {

    /** Every delivery Alice makes, per recipient, with when it was made. */
    private static final class Wire {
        final List<String[]> lines = new ArrayList<>(); // {to, line}
        final Map<String, Long> lastTo = new HashMap<>();

        int heartbeatsTo(Phase1Support.Signed s, String to) throws Exception {
            SwarmEndpoint reader = new SwarmEndpoint(to, SwarmConfig.defaults(), s.circles,
                    new InMemorySwarmBus().register(to), s.clock::get);
            reader.setSigning(s.signets.get("Bob"), s.signets); // own signet only matters for sending
            int n = 0;
            for (String[] l : lines) {
                if (!l[0].equals(to)) {
                    continue;
                }
                SwarmMessage m = reader.receiveLine(l[1]);
                if (m != null && SwarmControl.PING.equals(m.type()) && "hb".equals(m.body())) {
                    n++;
                }
            }
            return n;
        }
    }

    /** Records Alice's deliveries and feeds them back to Alice as "sent to that peer" liveness. */
    private static Wire wire(Phase1Support.Signed s) {
        Wire w = new Wire();
        s.bus.setInterceptor((from, to, line) -> {
            if ("Alice".equals(from)) {
                w.lines.add(new String[]{to, line});
                w.lastTo.put(to.toLowerCase(Locale.ROOT), s.clock.get());
            }
            return List.of(line);
        });
        s.aliceCtl.setRecentlySent((peer, window) -> {
            Long last = w.lastTo.get(peer.toLowerCase(Locale.ROOT));
            return last != null && s.clock.get() - last < window;
        });
        return w;
    }

    private static long aliceSeenByBob(Phase1Support.Signed s) {
        return s.bobCtl.peer("Alice").lastSeenMs();
    }

    @Test
    public void peerThatGetsOtherFramesGetsNoHeartbeat() throws Exception {
        Phase1Support.Signed s = new Phase1Support.Signed(new InMemorySwarmBus());
        Wire w = wire(s);
        w.lines.clear();
        long skipped0 = s.aliceCtl.heartbeatsSkipped();
        long worst = 0;
        for (int sec = 0; sec < 60; sec++) {
            s.clock.addAndGet(1_000L);
            if (sec % 5 == 0) {
                s.aliceEp.send("crew", "Bob", SwarmControl.PING, "ping", SwarmPriority.NORMAL);
            }
            s.tick();
            worst = Math.max(worst, s.clock.get() - aliceSeenByBob(s));
        }
        assertEquals("heartbeats to a peer that got a frame every 5 s", 0, w.heartbeatsTo(s, "Bob"));
        assertTrue("skipped " + (s.aliceCtl.heartbeatsSkipped() - skipped0),
                s.aliceCtl.heartbeatsSkipped() - skipped0 >= 5);
        assertTrue("Bob saw Alice " + worst + " ms ago at worst", worst <= 5_000L);
    }

    @Test
    public void idlePeerStillGetsAHeartbeatEveryInterval() throws Exception {
        Phase1Support.Signed s = new Phase1Support.Signed(new InMemorySwarmBus());
        Wire w = wire(s);
        w.lines.clear();
        long worst = 0;
        for (int sec = 0; sec < 60; sec++) {
            s.clock.addAndGet(1_000L);
            s.tick();
            worst = Math.max(worst, s.clock.get() - aliceSeenByBob(s));
        }
        int hb = w.heartbeatsTo(s, "Bob");
        assertTrue("heartbeats " + hb, hb >= 5 && hb <= 7);
        assertTrue("Bob saw Alice " + worst + " ms ago at worst", worst <= SwarmControl.HEARTBEAT_MS + 1_000L);
    }

    @Test
    public void onlyTheQuietPeerGetsADirectedHeartbeat() throws Exception {
        Phase1Support.Signed s = new Phase1Support.Signed(new InMemorySwarmBus(), "Carol");
        Wire w = wire(s);
        w.lines.clear();
        long sent0 = s.aliceCtl.heartbeatsSent();
        for (int sec = 0; sec < 60; sec++) {
            s.clock.addAndGet(1_000L);
            if (sec % 5 == 0) {
                s.aliceEp.send("crew", "Bob", SwarmControl.PING, "ping", SwarmPriority.NORMAL);
            }
            s.tick();
        }
        assertEquals("Bob got a frame every 5 s", 0, w.heartbeatsTo(s, "Bob"));
        int carol = w.heartbeatsTo(s, "Carol");
        assertTrue("Carol heartbeats " + carol, carol >= 5 && carol <= 7);
        // Directed, not broadcast: one line each, and none of them reached Bob.
        assertEquals(carol, s.aliceCtl.heartbeatsSent() - sent0);
    }
}
