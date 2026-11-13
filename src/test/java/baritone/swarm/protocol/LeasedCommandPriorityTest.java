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

import baritone.agent.AgentPolicy;
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmRateLimiter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Ostinato: a command bound to a lease goes at HIGH. Its lease is already running, so it must not wait behind
 * ordinary traffic; live b11r2 had one sit in the sender's queue until its deadline.
 */
public class LeasedCommandPriorityTest {

    private final AtomicLong clock = new AtomicLong(Phase1Support.T0);
    private final SwarmRateLimiter limiter = new SwarmRateLimiter(0.5, 5, false, 64, 0);
    private final List<SwarmPriority> priorities = new ArrayList<>();
    private final List<SwarmRateLimiter.Deadline> deadlines = new ArrayList<>();
    private final List<String> limiterLog = new ArrayList<>();

    private CommandProtocol alice() {
        limiter.setLog(limiterLog::add);
        CommandProtocol p = new CommandProtocol(
                new CommandProtocol.BotIdentity("Alice", "alicekey"),
                Set.of("Alice", "Bob"),
                new CommandProtocol.Wire() {
                    @Override
                    public boolean send(String to, String type, String body) {
                        return send(to, type, body, SwarmPriority.NORMAL, null);
                    }

                    @Override
                    public boolean send(String to, String type, String body, SwarmPriority priority) {
                        return send(to, type, body, priority, null);
                    }

                    @Override
                    public boolean send(String to, String type, String body, SwarmPriority priority,
                                        SwarmRateLimiter.Deadline deadline) {
                        if (!CommandProtocol.TYPE_CMD.equals(type)) {
                            return true; // only commands matter here
                        }
                        priorities.add(priority);
                        deadlines.add(deadline);
                        return limiter.offer(priority, "cmd", to, deadline);
                    }
                },
                new Phase1Support.RecordingExec(),
                new CommandProtocol.Policy("Alice", true, false, AgentPolicy.DEFAULT_TOOLS),
                clock::get,
                s -> { },
                1_000L);
        p.setGroups(Set.of("crew"));
        p.learnPeer("Bob", "bobkey", CapabilityDescriptor.gameplay());
        return p;
    }

    /** Ordinary lines already wait and the burst is spent; the leased command must leave next. */
    private void fillWithNormalTraffic() {
        for (int i = 0; i < 5; i++) {
            limiter.offer(SwarmPriority.NORMAL, "burst" + i, "Bob", null);
        }
        for (int i = 0; i < 5; i++) {
            assertNotNull(limiter.poll(0));
        }
        for (int i = 0; i < 4; i++) {
            limiter.offer(SwarmPriority.NORMAL, "lease " + i, "Bob", null);
        }
    }

    @Test
    public void leasedCommandGoesAtHighAndOvertakesNormalTraffic() {
        CommandProtocol alice = alice();
        fillWithNormalTraffic();
        UUID id = UUID.randomUUID();
        String r = alice.submitLeased("Bob", "goto 1 2 3", id);
        assertTrue(r, r.startsWith("swarm: sent "));
        assertEquals(List.of(SwarmPriority.HIGH), priorities);
        assertTrue(deadlines.get(0).label(), deadlines.get(0).label().equals("command " + id + " (leased)"));
        assertTrue(deadlines.get(0).expiresInMs() > 0);
        assertEquals("cmd", limiter.poll(2_000)); // the next token goes to the command
        assertTrue(limiterLog.isEmpty());
    }

    @Test
    public void plainCommandStillQueuesBehindNormalTraffic() {
        CommandProtocol alice = alice();
        fillWithNormalTraffic();
        String r = alice.submit("Bob", "goto 1 2 3");
        assertTrue(r, r.startsWith("swarm: sent "));
        assertEquals(List.of(SwarmPriority.NORMAL), priorities);
        assertFalse(deadlines.get(0).label().contains("(leased)"));
        assertEquals("lease 0", limiter.poll(2_000));
    }
}
