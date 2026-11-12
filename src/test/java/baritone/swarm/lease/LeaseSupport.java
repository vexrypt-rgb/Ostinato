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

import baritone.agent.AgentPolicy;
import baritone.api.command.CommandPermission;
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.swarm.protocol.CommandCatalog;
import baritone.swarm.protocol.CommandProtocol;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ostinato: Phase 2a lease fixture. Alice is the coordinator client, Bob the leased bot. Messages queue and
 * are delivered by {@link #pump()}, so tests can reorder or drop them. Both clocks move together.
 */
final class LeaseSupport {

    static final long T0 = 1_790_000_000_000L;

    static final class Msg {
        final String from;
        final String to;
        final String type;
        final String body;

        Msg(String from, String to, String type, String body) {
            this.from = from;
            this.to = to;
            this.type = type;
            this.body = body;
        }

        LeaseMessage lease() {
            return LeaseMessage.TYPE.equals(type) ? LeaseMessage.parse(body) : null;
        }

        @Override
        public String toString() {
            return from + "->" + to + " " + type + " " + body;
        }
    }

    static final class Exec implements CommandProtocol.Executor {
        final Map<String, Integer> byName = new LinkedHashMap<>();
        boolean confirm = true;
        ExecutionState poll;

        int count(String name) {
            Integer n = byName.get(name.toLowerCase(Locale.ROOT));
            return n == null ? 0 : n;
        }

        @Override
        public CommandPermission permissionOf(String name) {
            CommandCatalog.Entry e = describe(name);
            return e == null ? null : e.permission;
        }

        @Override
        public CommandCatalog.Entry describe(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            String key = name.toLowerCase(Locale.ROOT);
            return CommandCatalog.infer(key, CommandCatalog.defaultPermission(key));
        }

        @Override
        public boolean start(UUID id, String name, String args) {
            byName.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
            return true;
        }

        @Override
        public boolean confirmStopped(UUID id) {
            return confirm;
        }

        @Override
        public ExecutionState poll(UUID id) {
            return poll;
        }
    }

    final AtomicLong mono = new AtomicLong(1_000_000L);
    final AtomicLong wall = new AtomicLong(T0);
    final Deque<Msg> queue = new ArrayDeque<>();
    final List<Msg> delivered = new ArrayList<>();
    final List<Msg> sent = new ArrayList<>();
    final List<String> acks = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    final List<UUID> stopCalls = new ArrayList<>();
    boolean stopOk = true;
    /** Make the coordinator's submit refuse after the grant (a race the pre-check cannot see). */
    boolean refuseAfterGrant;
    /** Messages to these destinations are dropped (a dead link). */
    final java.util.Set<String> dropTo = new java.util.HashSet<>();

    final Path dir;
    final CoordinatorStore store;
    final Exec aliceExec = new Exec();
    final Exec bobExec = new Exec();
    CommandProtocol aliceP;
    CommandProtocol bobP;
    BotLeaseGuard guard;
    LeaseCoordinator coord;
    private boolean pumping;

    LeaseSupport(Path dir) {
        this.dir = dir;
        this.store = new CoordinatorStore(dir.resolve("coordinator.state"));
        aliceP = node("Alice", "alicekey", aliceExec);
        bobP = node("Bob", "bobkey", bobExec);
        aliceP.setGroups(Set.of("crew"));
        bobP.setGroups(Set.of("crew"));
        aliceP.learnPeer("Bob", "bobkey", CapabilityDescriptor.gameplay());
        bobP.learnPeer("Alice", "alicekey", CapabilityDescriptor.gameplay());
        guard = newGuard();
        bobP.setLeaseHooks(guard);
        guard.attach(bobP);
        coord = newCoordinator("Alice", "Alice");
    }

    BotLeaseGuard newGuard() {
        return new BotLeaseGuard((to, body) -> queue("Bob", to, LeaseMessage.TYPE, body), mono::get, log::add,
                () -> "Alice", id -> {
                    stopCalls.add(id);
                    return stopOk;
                }, null);
    }

    LeaseCoordinator newCoordinator(String self, String designated) {
        return new LeaseCoordinator(self, () -> designated, store,
                (to, body) -> queue(self, to, LeaseMessage.TYPE, body),
                new LeaseCoordinator.Commands() {
                    @Override
                    public String submitLeased(String b, String line, UUID id) {
                        return refuseAfterGrant ? "swarm: refused for test" : aliceP.submitLeased(b, line, id);
                    }

                    @Override
                    public String refusal(String b, String line) {
                        return aliceP.leaseRefusal(b, line);
                    }
                },
                mono::get, wall::get, log::add, () -> 30_000L, 3_000L, 0L, p -> false, () -> List.of("Bob"));
    }

    /** A relaunched bot: fresh guard, fresh protocol, nothing remembered. */
    void relaunchBob() {
        bobP = node("Bob", "bobkey", bobExec);
        bobP.setGroups(Set.of("crew"));
        bobP.learnPeer("Alice", "alicekey", CapabilityDescriptor.gameplay());
        guard = newGuard();
        bobP.setLeaseHooks(guard);
        guard.attach(bobP);
    }

    private CommandProtocol node(String name, String key, Exec exec) {
        return new CommandProtocol(new CommandProtocol.BotIdentity(name, key), Set.of("Alice", "Bob"),
                (to, type, body) -> queue(name, to, type, body), exec,
                new CommandProtocol.Policy("Alice", true, false, AgentPolicy.DEFAULT_TOOLS),
                wall::get, log::add, 1_000L);
    }

    boolean queue(String from, String to, String type, String body) {
        if (CommandProtocol.TYPE_ACK.equals(type)) {
            acks.add(body);
        }
        Msg msg = new Msg(from, to, type, body);
        sent.add(msg);
        queue.addLast(msg);
        return true;
    }

    void pump() {
        if (pumping) {
            return;
        }
        pumping = true;
        try {
            Msg m;
            while ((m = queue.pollFirst()) != null) {
                deliver(m);
            }
        } finally {
            pumping = false;
        }
    }

    void deliver(Msg m) {
        String to = "*".equals(m.to) || "all".equalsIgnoreCase(m.to) ? ("Alice".equals(m.from) ? "Bob" : "Alice") : m.to;
        if (dropTo.contains(to)) {
            return;
        }
        delivered.add(m);
        if (LeaseMessage.TYPE.equals(m.type)) {
            LeaseMessage lm = LeaseMessage.parse(m.body);
            if ("Bob".equals(to)) {
                guard.onMessage(m.from, lm);
            } else if ("Alice".equals(to)) {
                coord.onMessage(m.from, lm);
            }
            return;
        }
        CommandProtocol dest = "Bob".equals(to) ? bobP : "Alice".equals(to) ? aliceP : null;
        if (dest != null) {
            dest.receive("mem", m.from, m.type, m.body);
        }
    }

    /** Remove and return queued messages matching type and (optional) lease op. */
    List<Msg> take(String type, LeaseMessage.Op op) {
        List<Msg> out = new ArrayList<>();
        for (java.util.Iterator<Msg> it = queue.iterator(); it.hasNext(); ) {
            Msg m = it.next();
            if (!m.type.equals(type)) {
                continue;
            }
            if (op != null) {
                LeaseMessage lm = m.lease();
                if (lm == null || lm.op != op) {
                    continue;
                }
            }
            it.remove();
            out.add(m);
        }
        return out;
    }

    void advance(long ms) {
        mono.addAndGet(ms);
        wall.addAndGet(ms);
    }

    void tick() {
        coord.tick();
        guard.tick();
        bobP.pollExecutions();
        aliceP.pollExecutions();
        pump();
    }

    /** Advance in steps of {@code step} ms, ticking each time. */
    void run(long ms, long step) {
        for (long t = 0; t < ms; t += step) {
            advance(step);
            tick();
        }
    }

    /** Start the coordinator and get it through reconciliation. */
    void activate() {
        coord.start();
        pump();
        advance(6_100L);
        tick();
    }

    boolean logged(String part) {
        for (String l : log) {
            if (l.contains(part)) {
                return true;
            }
        }
        return false;
    }

    int loggedCount(String part) {
        int n = 0;
        for (String l : log) {
            if (l.contains(part)) {
                n++;
            }
        }
        return n;
    }

    boolean ackContains(String part) {
        for (String a : acks) {
            if (a.contains(part)) {
                return true;
            }
        }
        return false;
    }

    int sentCount(String from, LeaseMessage.Op op) {
        int n = 0;
        for (Msg m : delivered) {
            LeaseMessage lm = m.lease();
            if (lm != null && lm.op == op && m.from.equals(from)) {
                n++;
            }
        }
        return n;
    }

    static UUID leaseIdOf(String status) {
        int i = status.indexOf("(lease ");
        return UUID.fromString(status.substring(i + 7, status.indexOf(')', i)));
    }
}
