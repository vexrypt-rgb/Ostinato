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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Ostinato: Phase 1 telemetry yields to real traffic so it cannot hold a chat link at its cap. */
public class TelemetryThrottleTest {

    /** Frame types Alice and Bob put on the wire, decoded by a third copy of Bob's endpoint. */
    private static final class Wire {
        final List<String[]> lines = new ArrayList<>(); // {from, line}

        int count(Phase1Support.Signed s, String from, String type, String body) throws Exception {
            String peer = "Alice".equals(from) ? "Bob" : "Alice"; // read as the recipient would
            SwarmEndpoint reader = new SwarmEndpoint(peer, SwarmConfig.defaults(), s.circles,
                    new InMemorySwarmBus().register(peer), s.clock::get);
            reader.setSigning(s.signets.get(peer), s.signets);
            int n = 0;
            for (String[] l : lines) {
                if (!l[0].equals(from)) {
                    continue;
                }
                SwarmMessage m = reader.receiveLine(l[1]);
                if (m != null && m.type().equals(type) && (body == null || body.equals(m.body()))) {
                    n++;
                }
            }
            return n;
        }
    }

    private static Wire record(Phase1Support.Signed s) {
        Wire w = new Wire();
        s.bus.setInterceptor((from, to, line) -> {
            w.lines.add(new String[]{from, line});
            return List.of(line);
        });
        return w;
    }

    private static void run(Phase1Support.Signed s, long ms) {
        for (long t = 0; t < ms; t += 1_000L) {
            s.clock.addAndGet(1_000L);
            s.tick();
        }
    }

    @Test
    public void quietLinkHeartbeatsEveryTenSecondsWithSamplesAndNoPong() throws Exception {
        Phase1Support.Signed s = new Phase1Support.Signed(new InMemorySwarmBus());
        s.aliceCtl.setSampling(true, () -> true);
        Wire w = record(s);
        run(s, 60_000L);
        int hb = w.count(s, "Alice", SwarmControl.PING, "hb");
        int sts = w.count(s, "Alice", SwarmControl.STS, null);
        // Ostinato: samples have their own 30 s timer now; heartbeats no longer carry them.
        assertTrue("heartbeats " + hb, hb >= 5 && hb <= 7);
        assertTrue("samples " + sts, sts >= 1 && sts <= 3);
        assertEquals("no reply to a heartbeat", 0, w.count(s, "Bob", SwarmControl.PONG, null));
    }

    @Test
    public void busyLinkHeartbeatsEveryTwentySecondsAndNeverSamples() throws Exception {
        Phase1Support.Signed s = new Phase1Support.Signed(new InMemorySwarmBus());
        AtomicBoolean busy = new AtomicBoolean(true);
        s.aliceCtl.setSampling(true, () -> !busy.get());
        Wire w = record(s);
        long before = s.aliceCtl.samplesSent();
        run(s, 60_000L);
        int hb = w.count(s, "Alice", SwarmControl.PING, "hb");
        assertTrue("heartbeats " + hb, hb >= 2 && hb <= 3);
        assertEquals(0, w.count(s, "Alice", SwarmControl.STS, null));
        assertEquals(before, s.aliceCtl.samplesSent());
        // Busy still beats inside the peer timeout, so Bob never drops Alice.
        assertTrue(SwarmControl.BUSY_HEARTBEAT_MS < 30_000L);
    }
}
