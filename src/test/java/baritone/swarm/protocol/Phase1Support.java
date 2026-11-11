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
import baritone.api.command.CommandPermission;
import baritone.api.swarm.protocol.CapabilityDescriptor;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.swarm.SwarmConfig;
import baritone.swarm.SwarmControl;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.TestCircles;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.InMemorySwarmBus;
import baritone.swarm.transport.LocalSpoolTransport;
import baritone.swarm.transport.SwarmPriority;
import baritone.swarm.transport.SwarmTransport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Headless fixtures. No Minecraft client. */
final class Phase1Support {

    static final long T0 = 1_790_000_000_000L;

    private Phase1Support() {}

    static UUID idOf(String line) {
        String[] parts = line.split(" ");
        return UUID.fromString(parts[2]);
    }

    static String cmd(long now, UUID id, String issuer, String target, String name, String args) {
        return CommandProtocol.encodeCmd(id, issuer, target, name, args, now, now + 60_000L, false);
    }

    static final class RecordingExec implements CommandProtocol.Executor {
        final Map<String, Integer> byName = new LinkedHashMap<>();
        final Map<String, CommandCatalog.Entry> extra = new LinkedHashMap<>();
        int confirmCalls;
        boolean confirm = true;
        ExecutionState poll;

        int count(String name) {
            Integer n = byName.get(name.toLowerCase(Locale.ROOT));
            return n == null ? 0 : n;
        }

        int total() {
            int n = 0;
            for (int v : byName.values()) {
                n += v;
            }
            return n;
        }

        @Override
        public CommandPermission permissionOf(String name) {
            CommandCatalog.Entry entry = describe(name);
            return entry == null ? null : entry.permission;
        }

        @Override
        public CommandCatalog.Entry describe(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            String key = name.toLowerCase(Locale.ROOT);
            CommandCatalog.Entry over = extra.get(key);
            if (over != null) {
                return over;
            }
            return CommandCatalog.infer(key, CommandCatalog.defaultPermission(key));
        }

        @Override
        public boolean start(UUID id, String name, String args) {
            String key = name.toLowerCase(Locale.ROOT);
            byName.merge(key, 1, Integer::sum);
            return true;
        }

        @Override
        public boolean confirmStopped(UUID id) {
            confirmCalls++;
            return confirm;
        }

        @Override
        public ExecutionState poll(UUID id) {
            return poll;
        }
    }

    /** Two bots, direct delivery, no SIGIL. Use this for semantics. */
    static final class Pair {
        final AtomicLong clock = new AtomicLong(T0);
        final List<String> acks = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final RecordingExec aliceExec = new RecordingExec();
        final RecordingExec bobExec = new RecordingExec();
        final CommandProtocol alice;
        final CommandProtocol bob;

        Pair() {
            this("Alice");
        }

        /** Ostinato: ownerName is who OWNER commands allow. Cancel is SWARM, so a
         * non-owner member can still cancel a command it sent. */
        Pair(String ownerName) {
            alice = node("Alice", "alicekey", aliceExec, ownerName);
            bob = node("Bob", "bobkey", bobExec, ownerName);
            alice.setGroups(Set.of("crew"));
            bob.setGroups(Set.of("crew"));
            alice.learnPeer("Bob", "bobkey", CapabilityDescriptor.gameplay());
            bob.learnPeer("Alice", "alicekey", CapabilityDescriptor.gameplay());
        }

        private CommandProtocol node(String name, String key, RecordingExec exec, String ownerName) {
            return new CommandProtocol(
                    new CommandProtocol.BotIdentity(name, key),
                    Set.of("Alice", "Bob"),
                    (to, type, body) -> deliver(name, to, type, body),
                    exec,
                    new CommandProtocol.Policy(ownerName, true, false, AgentPolicy.DEFAULT_TOOLS),
                    clock::get,
                    log::add,
                    1_000L);
        }

        private boolean deliver(String from, String to, String type, String body) {
            if (CommandProtocol.TYPE_ACK.equals(type)) {
                acks.add(body);
            }
            CommandProtocol dest;
            if ("*".equals(to) || "all".equalsIgnoreCase(to)) {
                dest = "Alice".equals(from) ? bob : alice;
            } else if ("Bob".equals(to)) {
                dest = bob;
            } else if ("Alice".equals(to)) {
                dest = alice;
            } else {
                return false;
            }
            if (dest == null) {
                return false;
            }
            dest.receive("mem", from, type, body);
            return true;
        }
    }

    /** Signed link on a bus or a pair of spool directories. */
    static final class Signed {
        final AtomicLong clock = new AtomicLong(T0);
        final SwarmRoster roster;
        final Map<String, SigilCircle> circles = new LinkedHashMap<>();
        final Map<String, SigilEd25519> signets = new LinkedHashMap<>();
        final RecordingExec aliceExec = new RecordingExec();
        final RecordingExec bobExec = new RecordingExec();
        final SwarmEndpoint aliceEp;
        final SwarmEndpoint bobEp;
        final SwarmControl aliceCtl;
        final SwarmControl bobCtl;
        final CommandProtocol aliceP;
        final CommandProtocol bobP;
        final InMemorySwarmBus bus;
        final LocalSpoolTransport aliceSpool;
        final LocalSpoolTransport bobSpool;

        Signed(InMemorySwarmBus bus) throws Exception {
            this(bus, null, null, null);
        }

        Signed(Path spoolRoot) throws Exception {
            this(null, spoolRoot, new LocalSpoolTransport(spoolRoot, "Alice"), new LocalSpoolTransport(spoolRoot, "Bob"));
        }

        private Signed(InMemorySwarmBus bus, Path ignored, LocalSpoolTransport aliceSpool, LocalSpoolTransport bobSpool)
                throws Exception {
            this.bus = bus;
            this.aliceSpool = aliceSpool;
            this.bobSpool = bobSpool;
            roster = SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob lead=Alice\n");
            circles.put("crew", TestCircles.alpha());
            signets.put("Alice", SigilEd25519.testSignet("Alice-DO-NOT-USE"));
            signets.put("Bob", SigilEd25519.testSignet("Bob-DO-NOT-USE"));
            SwarmTransport aliceT = bus != null ? bus.register("Alice") : aliceSpool;
            SwarmTransport bobT = bus != null ? bus.register("Bob") : bobSpool;
            aliceEp = endpoint("Alice", aliceT);
            bobEp = endpoint("Bob", bobT);
            aliceCtl = control("Alice", aliceEp);
            bobCtl = control("Bob", bobEp);
            aliceP = protocol("Alice", aliceEp, aliceExec);
            bobP = protocol("Bob", bobEp, bobExec);
            aliceCtl.setCommandProtocol(aliceP);
            bobCtl.setCommandProtocol(bobP);
            aliceP.advertise();
            bobP.advertise();
            tick();
            tick();
        }

        void tick() {
            aliceCtl.tick();
            bobCtl.tick();
        }

        private SwarmEndpoint endpoint(String name, SwarmTransport transport) throws Exception {
            SwarmEndpoint endpoint = new SwarmEndpoint(name, SwarmConfig.defaults(), circles, transport, clock::get);
            endpoint.setMemberCheck(roster::isMember);
            endpoint.setSigning(signets.get(name), signets);
            return endpoint;
        }

        private SwarmControl control(String name, SwarmEndpoint endpoint) {
            return new SwarmControl(roster, endpoint, new SwarmControl.LocalStatus() {
                @Override public String name() { return name; }
                @Override public String position() { return "0,64,0"; }
                @Override public String process() { return "idle"; }
            }, clock::get, s -> { }, null);
        }

        private CommandProtocol protocol(String name, SwarmEndpoint endpoint, RecordingExec exec) {
            String key = CapabilityDescriptor.hex(signets.get(name).keyid());
            CommandProtocol protocol = new CommandProtocol(
                    new CommandProtocol.BotIdentity(name, key),
                    Set.of("Alice", "Bob"),
                    new CommandProtocol.Wire() { // Ostinato: propagate protocol priorities through SIGIL.
                        @Override
                        public boolean send(String to, String type, String body) {
                            return send(to, type, body, SwarmPriority.NORMAL);
                        }

                        @Override
                        public boolean send(String to, String type, String body, SwarmPriority priority) {
                            try {
                                String dest = "*".equals(to) || "all".equalsIgnoreCase(to) ? SwarmFrame.BROADCAST : to;
                                endpoint.send("crew", dest, type, body, priority); // Ostinato:
                                return true;
                            } catch (Exception e) {
                                return false;
                            }
                        }
                    },
                    exec,
                    new CommandProtocol.Policy("Alice", true, false, AgentPolicy.DEFAULT_TOOLS),
                    clock::get,
                    s -> { },
                    1_000L);
            protocol.setGroups(Set.of("crew"));
            protocol.setLocalCapabilities(CapabilityDescriptor.gameplay());
            for (Map.Entry<String, SigilEd25519> e : signets.entrySet()) {
                protocol.pinSignet(e.getKey(), CapabilityDescriptor.hex(e.getValue().keyid()));
            }
            return protocol;
        }
    }
}
