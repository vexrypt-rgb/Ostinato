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
import baritone.api.swarm.protocol.ISwarmStatusView;
import baritone.api.swarm.protocol.ObservationState;
import baritone.swarm.SwarmControl;
import baritone.swarm.SwarmControlTest;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.TestCircles;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.frame.SwarmReject;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.InMemorySwarmBus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ostinato: Phase 1 quantitative gate. Each {@code gate_*} method is one row of that gate.
 * Authentication in the in-memory cases is the caller's job, matching production (SIGIL, then this class).
 * The SIGIL test uses the real endpoint.
 */
public class Phase1GateTest {

    /**
     * Written before the timed section below. Do not edit these to fit a measured result.
     * A sample times {@link #PREREGISTERED_BATCH} operations. The protocol median must be
     * {@code <= plainMedian * PREREGISTERED_MEDIAN_FACTOR + PREREGISTERED_ADDITIVE_MS}.
     */
    static final int PREREGISTERED_BATCH = 20;
    static final double PREREGISTERED_MEDIAN_FACTOR = 80.0;
    static final double PREREGISTERED_ADDITIVE_MS = 5.0;
    static final int PREREGISTERED_SAMPLES = 21;

    @Test
    public void gate_unknownIsNeverAnExecutionResult() {
        try {
            ExecutionState.parse("unknown");
            fail("unknown parsed as an execution state");
        } catch (IllegalArgumentException expected) {
            // observation only
        }
        assertEquals(ObservationState.UNKNOWN, ObservationState.parse("unknown"));
        for (ExecutionState e : ExecutionState.values()) {
            assertFalse(e.label(), "unknown".equals(e.label()));
        }
    }

    @Test
    public void gate_unexpectedExecution_duplicateDeliveryTimesN() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        String body = w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false);
        for (int i = 0; i < 8; i++) {
            w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, body);
        }
        assertEquals(1, w.bob.startCount());
        assertEquals(1, w.exec.starts.get());
        assertEquals(ExecutionState.RUNNING, w.bob.lookup(id).execution);
        assertEquals(ObservationState.CONFIRMED, w.bob.lookup(id).observation);
    }

    @Test
    public void gate_unexpectedExecution_crossTransportDuplicate() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        String body = w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false);
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, body);
        w.bob.receive("spool", "Alice", CommandProtocol.TYPE_CMD, body);
        assertEquals(1, w.exec.starts.get());
        assertTrue(w.logContains("duplicate"));
    }

    @Test
    public void gate_unexpectedExecution_tamperedReplay() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false));
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "goto", "9 9 9", false));
        assertEquals(1, w.exec.starts.get());
        CommandProtocol.Tracked t = w.bob.lookup(id);
        assertEquals(ExecutionState.RUNNING, t.execution);
        assertNull(t.rejectReason);
        assertTrue(w.joinedAcks(), w.joinedAcks().contains("tampered-replay"));
    }

    @Test
    public void gate_unexpectedExecution_forged() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        // Body names Alice. The authenticated sender is Mallory.
        w.bob.receive("ipc", "Mallory", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false));
        assertEquals(0, w.exec.starts.get());
        assertNull(w.bob.lookup(id));
        assertTrue(w.joinedAcks(), w.joinedAcks().contains("forged"));
    }

    @Test
    public void gate_unexpectedExecution_unknownMember() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        w.bob.receive("ipc", "Carol", CommandProtocol.TYPE_CMD, w.cmd(id, "Carol", "Bob", "goto", "1 2 3", false));
        assertEquals(0, w.exec.starts.get());
        assertNull(w.bob.lookup(id));
        assertTrue(w.joinedAcks(), w.joinedAcks().contains("unknown-member"));
    }

    @Test
    public void gate_unexpectedExecution_wrongTarget() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Carol", "goto", "1 2 3", false));
        assertEquals(0, w.exec.starts.get());
        CommandProtocol.Tracked t = w.bob.lookup(id);
        assertNotNull(t);
        assertFalse(t.started);
        assertEquals("wrong-target", t.rejectReason);
        assertEquals(ExecutionState.FAILED, t.execution);
    }

    @Test
    public void gate_unexpectedExecution_expired() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        long now = w.clock.get();
        String past = CommandProtocol.encodeCmd(id, "Alice", "Bob", "goto", "1 2 3", now - 5_000, now - 1, false);
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, past);
        assertEquals(0, w.exec.starts.get());
        assertEquals("expired", w.bob.lookup(id).rejectReason);
        UUID id2 = UUID.randomUUID();
        String backwards = CommandProtocol.encodeCmd(id2, "Alice", "Bob", "goto", "1 2 3", now + 50_000, now + 1_000, false);
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, backwards);
        assertEquals(0, w.exec.starts.get());
        assertEquals("expired", w.bob.lookup(id2).rejectReason);
    }

    @Test
    public void gate_unexpectedExecution_unauthorized() {
        World w = World.create();
        UUID setId = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(setId, "Alice", "Bob", "set", "allowSprint true", false));
        assertEquals(0, w.exec.starts.get());
        assertEquals("unauthorized", w.bob.lookup(setId).rejectReason);
        UUID boom = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(boom, "Alice", "Bob", "explode", "1", false));
        assertEquals(0, w.exec.starts.get());
        assertEquals("unauthorized", w.bob.lookup(boom).rejectReason);
        // A roster member may still run a swarm-tagged command. One execution, not zero for the wrong reason.
        UUID ok = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(ok, "Alice", "Bob", "goto", "1 2 3", false));
        assertEquals(1, w.exec.starts.get());
    }

    @Test
    public void gate_unexpectedExecution_dryRunNeverExecutes() {
        World w = World.create();
        UUID id = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "goto", "1 2 3", true));
        assertEquals(0, w.exec.starts.get());
        assertEquals("preview", w.bob.lookup(id).rejectReason);
        World ai = World.create(new CommandProtocol.Policy("", true, true, AgentPolicy.DEFAULT_TOOLS));
        UUID settings = UUID.randomUUID();
        ai.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, ai.cmd(settings, "Alice", "Bob", "settings", "", false));
        assertEquals(0, ai.exec.starts.get());
        assertEquals("preview", ai.bob.lookup(settings).rejectReason);
    }

    @Test
    public void gate_unexpectedExecution_safetyOutranksSwarm() {
        String[] reasons = {
                "outranked-by-immediate-safety",
                "outranked-by-owner-emergency",
                "outranked-by-local-survival"
        };
        for (int i = 0; i < reasons.length; i++) {
            World w = World.create();
            if (i == 0) {
                w.bob.setImmediateSafety(true);
            } else if (i == 1) {
                w.bob.setOwnerEmergency(true);
            } else {
                w.bob.setLocalSurvival(true);
            }
            UUID id = UUID.randomUUID();
            w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false));
            assertEquals(reasons[i], 0, w.exec.starts.get());
            assertEquals(reasons[i], 0, w.exec.confirms.get());
            assertEquals(reasons[i], reasons[i], w.bob.lookup(id).rejectReason);
        }
        World w = World.create();
        UUID id = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false));
        w.bob.setOwnerEmergency(true);
        UUID cid = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(cid, "Alice", "Bob", "cancel", id.toString(), false));
        assertEquals(0, w.exec.confirms.get());
        assertEquals(ExecutionState.RUNNING, w.bob.lookup(id).execution);
        assertEquals(ObservationState.CONFIRMED, w.bob.lookup(id).observation);
        assertEquals("outranked-by-owner-emergency", w.bob.lookup(cid).rejectReason);
    }

    @Test
    public void gate_duplicateActiveTaskOwnership_concurrentDelivery() throws Exception {
        World w = World.create();
        UUID id = UUID.randomUUID();
        String body = w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Runnable job = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        pool.submit(job);
        pool.submit(job);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, w.exec.starts.get());
        assertEquals(1, w.bob.startCount());
    }

    @Test
    public void gate_stopScenarios_allCanceledConfirmedOrRunningUnknown() {
        // Confirmed stop.
        World confirmed = World.create();
        UUID id = UUID.randomUUID();
        confirmed.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, confirmed.cmd(id, "Alice", "Bob", "goto", "1 2 3", false));
        confirmed.exec.confirm = true;
        UUID cid = UUID.randomUUID();
        String cancel = confirmed.cmd(cid, "Alice", "Bob", "cancel", id.toString(), false);
        confirmed.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, cancel);
        assertState(confirmed.bob.lookup(id), ExecutionState.CANCELED, ObservationState.CONFIRMED);
        assertState(confirmed.bob.lookup(cid), ExecutionState.SUCCEEDED, ObservationState.CONFIRMED);
        confirmed.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, cancel);
        assertEquals(1, confirmed.exec.confirms.get());

        // Disconnect inside confirm, even when the process reports it stopped.
        World mid = World.create();
        UUID midId = UUID.randomUUID();
        mid.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, mid.cmd(midId, "Alice", "Bob", "goto", "1 2 3", false));
        mid.exec.confirm = true;
        mid.exec.onConfirm = mid.bob::onDisconnect;
        UUID midCancel = UUID.randomUUID();
        mid.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, mid.cmd(midCancel, "Alice", "Bob", "cancel", midId.toString(), false));
        assertState(mid.bob.lookup(midId), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertState(mid.bob.lookup(midCancel), ExecutionState.RUNNING, ObservationState.UNKNOWN);

        // Link already down, so confirm is not even attempted.
        World dropped = World.create();
        UUID dropId = UUID.randomUUID();
        dropped.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, dropped.cmd(dropId, "Alice", "Bob", "goto", "1 2 3", false));
        dropped.bob.onDisconnect();
        int confirms = dropped.exec.confirms.get();
        UUID dropCancel = UUID.randomUUID();
        dropped.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, dropped.cmd(dropCancel, "Alice", "Bob", "stop", dropId.toString(), false));
        assertEquals(confirms, dropped.exec.confirms.get());
        assertState(dropped.bob.lookup(dropId), ExecutionState.RUNNING, ObservationState.UNKNOWN);
        assertState(dropped.bob.lookup(dropCancel), ExecutionState.RUNNING, ObservationState.UNKNOWN);
    }

    @Test
    public void gate_injectedDisconnects_everyCaseKnownSafeState() {
        World running = World.create();
        UUID id = UUID.randomUUID();
        running.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, running.cmd(id, "Alice", "Bob", "goto", "1 2 3", false));
        running.bob.onDisconnect();
        assertEquals(ExecutionState.RUNNING, running.bob.lookup(id).execution);
        assertEquals(ObservationState.UNKNOWN, running.bob.lookup(id).observation);
        assertFalse(running.bob.lookup(id).execution.terminal());

        World done = World.create();
        UUID echo = UUID.randomUUID();
        done.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, done.cmd(echo, "Alice", "Bob", "echo", "hi", false));
        assertEquals(ExecutionState.SUCCEEDED, done.bob.lookup(echo).execution);
        done.bob.onDisconnect();
        assertEquals(ExecutionState.SUCCEEDED, done.bob.lookup(echo).execution);
        assertEquals(ObservationState.CONFIRMED, done.bob.lookup(echo).observation);

        // Stop asked, process still going, link still up: not canceled, observation unknown.
        World linked = World.create();
        UUID live = UUID.randomUUID();
        linked.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, linked.cmd(live, "Alice", "Bob", "goto", "1 2 3", false));
        linked.exec.confirm = false;
        UUID liveCancel = UUID.randomUUID();
        linked.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, linked.cmd(liveCancel, "Alice", "Bob", "cancel", live.toString(), false));
        // Ostinato: the stop is re-checked each tick; it settles as unknown once the window runs out.
        for (int i = 0; i < CommandProtocol.CANCEL_CONFIRM_WINDOW_TICKS; i++) {
            linked.bob.pollExecutions();
        }
        assertEquals(ExecutionState.RUNNING, linked.bob.lookup(live).execution);
        assertEquals(ObservationState.UNKNOWN, linked.bob.lookup(live).observation);
        assertEquals(ExecutionState.RUNNING, linked.bob.lookup(liveCancel).execution);
        assertEquals(ObservationState.UNKNOWN, linked.bob.lookup(liveCancel).observation);

        for (String name : new String[]{"mine", "place", "drop", "pickup", "craft", "use"}) {
            assertFalse(name, CommandProtocol.nonIdempotent("echo"));
            assertTrue(name, CommandProtocol.nonIdempotent(name));
        }
    }

    @Test
    public void gate_noAutoRetryMineOrPlaceOnUnknown() {
        World w = World.create();
        String sent = w.alice.submit("Bob", "mine 1");
        w.exchange();
        UUID id = idOf(sent);
        assertEquals(1, w.exec.starts.get());
        assertEquals(ExecutionState.RUNNING, w.bob.lookup(id).execution);
        w.alice.onDisconnect();
        w.bob.onDisconnect();
        assertEquals(ObservationState.UNKNOWN, w.alice.lookupSent(id).observation);
        assertEquals(ObservationState.UNKNOWN, w.bob.lookup(id).observation);
        assertFalse(w.alice.mayRetry(id, "mine"));
        assertFalse(w.alice.mayRetry(id, "place"));
        String again = w.alice.submit("Bob", "mine 2", w.clock.get() + 60_000L, false, id);
        assertTrue(again, again.contains("not retrying"));
        assertEquals(1, w.exec.starts.get());
        assertTrue(w.aliceLane.q.isEmpty());
    }

    @Test
    public void gate_atLeastOnce_retriesSameIdAndGivesUpAtDeadline() {
        World w = World.create();
        String sent = w.alice.submit("Bob", "goto 1 2 3", w.clock.get() + 5_000L, false, null);
        UUID id = idOf(sent);
        CommandProtocol.Sent first = w.alice.lookupSent(id);
        assertEquals(1, first.sends);
        assertFalse(first.acked);
        assertEquals(0, w.alice.retried()); // Ostinato: the first send is not a retry.
        String body = first.body;
        w.clock.set(first.nextRetry); // Ostinato: respect queued-send backoff.
        w.alice.pump();
        CommandProtocol.Sent retried = w.alice.lookupSent(id);
        assertEquals(body, retried.body);
        assertEquals(2, retried.sends);
        assertEquals(1, w.alice.retried()); // Ostinato: link monitor counter.
        assertFalse(retried.acked);
        assertTrue(w.aliceLane.q.size() >= 1);
        w.clock.set(retried.deadline);
        w.alice.pump();
        assertTrue(w.alice.lookupSent(id).gaveUp);
        assertFalse(w.alice.lookupSent(id).acked);
        assertEquals(2, w.alice.lookupSent(id).sends);
        assertTrue(w.alice.outcomeLine(id.toString()), w.alice.outcomeLine(id.toString()).contains("gave up"));
        // A transport accept was never an ack. Delivering the original body still runs once.
        w.exchange();
        assertEquals(1, w.exec.starts.get());
    }

    @Test
    public void gate_statusViewClose_doesNotInterruptExecution() {
        World w = World.create();
        ISwarmStatusView view = w.bob.openStatusView();
        assertTrue(view.isOpen());
        UUID id = UUID.randomUUID();
        w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "goto", "1 2 3", false));
        int starts = w.bob.startCount();
        ExecutionState execution = w.bob.lookup(id).execution;
        assertFalse(view.lines().isEmpty());
        view.close();
        assertFalse(view.isOpen());
        assertTrue(view.lines().isEmpty());
        assertEquals(0, w.bob.openViewCount());
        assertEquals(starts, w.bob.startCount());
        assertEquals(execution, w.bob.lookup(id).execution);
        assertEquals(ExecutionState.RUNNING, w.bob.lookup(id).execution);
        boolean mentioned = false;
        for (String line : w.bob.statusLines()) {
            if (line.contains(id.toString())) {
                mentioned = true;
            }
        }
        assertTrue(mentioned);
    }

    @Test
    public void gate_performance_withinPreregisteredTolerance() {
        World w = World.create();
        for (int i = 0; i < 40; i++) {
            UUID id = UUID.randomUUID();
            w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "echo", "w", false));
            w.exec.start(id, "echo", "w");
        }
        double[] protocol = new double[PREREGISTERED_SAMPLES];
        double[] plain = new double[PREREGISTERED_SAMPLES];
        for (int s = 0; s < PREREGISTERED_SAMPLES; s++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < PREREGISTERED_BATCH; i++) {
                UUID id = UUID.randomUUID();
                w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "echo", "p", false));
            }
            protocol[s] = (System.nanoTime() - t0) / 1_000_000.0;
            long t1 = System.nanoTime();
            for (int i = 0; i < PREREGISTERED_BATCH; i++) {
                w.exec.start(UUID.randomUUID(), "echo", "p");
            }
            plain[s] = (System.nanoTime() - t1) / 1_000_000.0;
        }
        double protocolMedian = median(protocol);
        double plainMedian = median(plain);
        double limit = plainMedian * PREREGISTERED_MEDIAN_FACTOR + PREREGISTERED_ADDITIVE_MS;
        assertTrue("protocolMedianMs=" + protocolMedian + " plainMedianMs=" + plainMedian + " limitMs=" + limit,
                protocolMedian <= limit);
    }

    @Test
    public void gate_clientCrashesAndOomStayZero() {
        World w = World.create();
        int n = 100;
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            w.bob.receive("ipc", "Alice", CommandProtocol.TYPE_CMD, w.cmd(id, "Alice", "Bob", "echo", Integer.toString(i), false));
            assertNotNull(w.bob.lookup(id).execution);
            assertFalse("unknown".equals(w.bob.lookup(id).execution.name()));
        }
        assertEquals(n, w.exec.starts.get());
        assertEquals(0, w.exec.failures.get());
    }

    @Test
    public void gate_sigil_unauthenticatedForgedReplayNeverExecute() throws Exception {
        AtomicLong clock = new AtomicLong(1_790_000_000_000L);
        SwarmRoster roster = SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob lead=Alice\n");
        Map<String, SigilCircle> circles = new LinkedHashMap<String, SigilCircle>();
        circles.put("crew", TestCircles.alpha());
        InMemorySwarmBus bus = new InMemorySwarmBus();
        List<String> log = new ArrayList<String>();
        SwarmControl alice = control(roster, circles, bus, "Alice", clock, log);
        SwarmControl bob = control(roster, circles, bus, "Bob", clock, log);
        FakeExec exec = new FakeExec();
        List<String> acks = new ArrayList<String>();
        CommandProtocol protocol = new CommandProtocol(
                new CommandProtocol.BotIdentity("Bob", "sigiltest"),
                Set.of("Alice", "Bob"),
                (to, type, body) -> {
                    acks.add(type + " " + body);
                    return true;
                },
                exec,
                CommandProtocol.Policy.defaults(),
                clock::get,
                log::add,
                1_000L);
        bob.setCommandProtocol(protocol);

        UUID id = UUID.randomUUID();
        String body = CommandProtocol.encodeCmd(id, "Alice", "Bob", "goto", "1 2 3", clock.get(), clock.get() + 60_000L, false);
        bus.setInterceptor((from, to, line) -> Arrays.asList(line, line));
        alice.endpoint().send("crew", "Bob", CommandProtocol.TYPE_CMD, body);
        bob.tick();
        assertEquals(1, exec.starts.get());
        assertTrue(bob.endpoint().rejectCount(SwarmReject.REPLAY) >= 1);

        bus.setInterceptor((from, to, line) -> Collections.singletonList("not-a-sealed-frame"));
        int starts = exec.starts.get();
        alice.endpoint().send("crew", "Bob", CommandProtocol.TYPE_CMD,
                CommandProtocol.encodeCmd(UUID.randomUUID(), "Alice", "Bob", "goto", "4 5 6", clock.get(), clock.get() + 60_000L, false));
        bob.tick();
        assertEquals(starts, exec.starts.get());
        boolean rejected = bob.endpoint().lastReject() != null;
        if (!rejected) {
            for (Long n : bob.endpoint().rejectCounts().values()) {
                if (n != null && n.longValue() > 0) {
                    rejected = true;
                }
            }
        }
        boolean logged = false;
        for (String line : log) {
            if (line.contains("receive failed") || line.contains("rejected")) {
                logged = true;
            }
        }
        assertTrue(rejected || logged);

        bus.setInterceptor((from, to, line) -> Collections.singletonList(line));
        SwarmEndpoint eve = new SwarmEndpoint("Eve", SwarmControlTest.unsignedCfg(), circles, bus.register("Eve"), clock::get);
        eve.send("crew", "Bob", CommandProtocol.TYPE_CMD,
                CommandProtocol.encodeCmd(UUID.randomUUID(), "Eve", "Bob", "goto", "7 8 9", clock.get(), clock.get() + 60_000L, false));
        bob.tick();
        assertEquals(starts, exec.starts.get());
        assertTrue(bob.endpoint().rejectCount(SwarmReject.NOT_MEMBER) >= 1);
        assertEquals(1, protocol.startCount());
    }

    private static void assertState(CommandProtocol.Tracked tracked, ExecutionState execution, ObservationState observation) {
        assertNotNull(tracked);
        assertEquals(execution, tracked.execution);
        assertEquals(observation, tracked.observation);
        assertFalse("unknown".equals(tracked.execution.name()));
    }

    private static UUID idOf(String line) {
        String[] parts = line.split(" ");
        assertTrue(line, parts.length >= 3);
        return UUID.fromString(parts[2]);
    }

    private static double median(double[] samples) {
        double[] copy = Arrays.copyOf(samples, samples.length);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static SwarmControl control(SwarmRoster roster, Map<String, SigilCircle> circles, InMemorySwarmBus bus,
                                        String name, AtomicLong clock, List<String> log) throws Exception {
        SwarmEndpoint endpoint = new SwarmEndpoint(name, SwarmControlTest.unsignedCfg(), circles, bus.register(name), clock::get);
        endpoint.setMemberCheck(roster::isMember);
        return new SwarmControl(roster, endpoint, new SwarmControl.LocalStatus() {
            @Override
            public String name() { return name; }
            @Override
            public String position() { return "0,64,0"; }
            @Override
            public String process() { return "idle"; }
        }, clock::get, log::add, null);
    }

    /** One Alice and one Bob. Bob's executor is shared so a send from Alice can be counted. */
    static final class World {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final FakeExec exec = new FakeExec();
        final Lane aliceLane = new Lane();
        final Lane bobLane = new Lane();
        final List<String> log = new ArrayList<String>();
        final CommandProtocol alice;
        final CommandProtocol bob;

        private World(CommandProtocol.Policy policy) {
            Set<String> members = Set.of("Alice", "Bob");
            this.alice = new CommandProtocol(new CommandProtocol.BotIdentity("Alice", "aaaa"), members, aliceLane, exec,
                    policy, clock::get, log::add, 1_000L);
            this.bob = new CommandProtocol(new CommandProtocol.BotIdentity("Bob", "bbbb"), members, bobLane, exec,
                    policy, clock::get, log::add, 1_000L);
            CapabilityDescriptor caps = CapabilityDescriptor.gameplay();
            this.alice.learnPeer("Bob", "bbbb", caps);
            this.bob.learnPeer("Alice", "aaaa", caps);
        }

        static World create() {
            // A named owner. An empty owner name allows every owner-tagged command.
            return new World(new CommandProtocol.Policy("Carol", true, false, AgentPolicy.DEFAULT_TOOLS));
        }

        static World create(CommandProtocol.Policy policy) {
            return new World(policy);
        }

        String cmd(UUID id, String issuer, String target, String name, String args, boolean dry) {
            return CommandProtocol.encodeCmd(id, issuer, target, name, args, clock.get(), clock.get() + 60_000L, dry);
        }

        void exchange() {
            for (int i = 0; i < 8; i++) {
                if (aliceLane.q.isEmpty() && bobLane.q.isEmpty()) {
                    return;
                }
                drain(aliceLane, "Alice", bob, "ipc");
                drain(bobLane, "Bob", alice, "ipc");
            }
        }

        boolean logContains(String needle) {
            for (String line : log) {
                if (line.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        String joinedAcks() {
            StringBuilder b = new StringBuilder();
            for (String[] m : aliceLane.q) {
                b.append(m[1]).append(' ').append(m[2]).append('\n');
            }
            for (String[] m : bobLane.q) {
                b.append(m[1]).append(' ').append(m[2]).append('\n');
            }
            return b.toString();
        }

        private static void drain(Lane lane, String from, CommandProtocol dest, String transport) {
            List<String[]> batch;
            synchronized (lane.q) {
                batch = new ArrayList<String[]>(lane.q);
                lane.q.clear();
            }
            for (String[] m : batch) {
                dest.receive(transport, from, m[1], m[2]);
            }
        }
    }

    static final class Lane implements CommandProtocol.Wire {
        final List<String[]> q = Collections.synchronizedList(new ArrayList<String[]>());

        @Override
        public boolean send(String to, String type, String body) {
            q.add(new String[]{to, type, body});
            return true;
        }
    }

    static final class FakeExec implements CommandProtocol.Executor {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger confirms = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final Map<String, CommandPermission> perms = new HashMap<String, CommandPermission>();
        volatile boolean confirm = true;
        volatile Runnable onConfirm;

        FakeExec() {
            perms.put("goto", CommandPermission.SWARM);
            perms.put("mine", CommandPermission.SWARM);
            perms.put("cancel", CommandPermission.SWARM);
            perms.put("stop", CommandPermission.SWARM);
            perms.put("follow", CommandPermission.SWARM);
            perms.put("echo", CommandPermission.SWARM);
            perms.put("help", CommandPermission.OWNER);
            perms.put("set", CommandPermission.OWNER);
            perms.put("settings", CommandPermission.AI);
            perms.put("explode", CommandPermission.AI);
        }

        @Override
        public CommandPermission permissionOf(String name) {
            return perms.get(name);
        }

        @Override
        public boolean start(UUID id, String name, String args) {
            starts.incrementAndGet();
            return true;
        }

        @Override
        public boolean confirmStopped(UUID id) {
            confirms.incrementAndGet();
            Runnable hook = onConfirm;
            if (hook != null) {
                try {
                    hook.run();
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                    return false;
                }
            }
            return confirm;
        }

        @Override
        public ExecutionState poll(UUID id) {
            return null;
        }
    }
}
