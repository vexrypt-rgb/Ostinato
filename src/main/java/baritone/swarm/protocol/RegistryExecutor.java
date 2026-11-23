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

import baritone.api.IBaritone;
import baritone.api.command.CommandPermission;
import baritone.api.command.ICommand;
import baritone.api.command.manager.ICommandManager;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.ProcessOutcome; // Ostinato:
import baritone.api.pathing.goals.Goal; // Ostinato:
import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ExternalCommandHook;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Ostinato: runs a swarm command through {@link ICommandManager#execute}, the single registry entry point.
 * TenorClef {@code @} commands go through {@link ExternalCommandHook} only when the registry has no match.
 * This class does not cancel survival or an owner pause; it only reports whether a process is still active.
 */
public final class RegistryExecutor implements CommandProtocol.Executor {

    private final IBaritone baritone;
    private final Supplier<ExternalCommandHook> hook;
    private final Map<UUID, String> started = new ConcurrentHashMap<>();
    private final Map<UUID, Goal> goals = new ConcurrentHashMap<>(); // Ostinato:
    private final Set<UUID> seenActivity = ConcurrentHashMap.newKeySet(); // Ostinato:
    // Ostinato: the process that ran each command, and the outcome request numbers before it started, so a command's
    // success comes from that process's own recorded outcome for this request, never from the process going idle.
    private final Map<UUID, IBaritoneProcess> processOf = new ConcurrentHashMap<>();
    private final Map<UUID, Map<IBaritoneProcess, Long>> requestBefore = new ConcurrentHashMap<>();
    private final Set<UUID> unverifiedIdle = ConcurrentHashMap.newKeySet();
    private final Map<String, CommandCatalog.Entry> atCommands;

    public RegistryExecutor(IBaritone baritone, Supplier<ExternalCommandHook> hook) {
        this(baritone, hook, null);
    }

    public RegistryExecutor(IBaritone baritone, Supplier<ExternalCommandHook> hook, String atCatalog) {
        this.baritone = baritone;
        this.hook = hook == null ? () -> null : hook;
        this.atCommands = CommandCatalog.index(CommandCatalog.parse(atCatalog));
    }

    private ICommandManager commands() {
        return baritone.getCommandManager();
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
        ICommand command = commands().getCommand(name);
        if (command != null) {
            String key = name.toLowerCase(java.util.Locale.ROOT);
            CommandPermission perm = command.getPermission();
            // Ostinato: ICommand#cancel is OWNER (local #cancel). Swarm cancel of a
            // command a peer sent has to be SWARM or the peer's cancel is unauthorized.
            if ("cancel".equals(key) || "forcecancel".equals(key)) {
                perm = CommandPermission.SWARM;
            }
            return CommandCatalog.infer(name, perm);
        }
        CommandCatalog.Entry at = atCommands.get(name.toLowerCase(java.util.Locale.ROOT));
        if (at != null) {
            return at;
        }
        ExternalCommandHook extra = hook.get();
        if (extra != null && extra.knows(name)) {
            return CommandCatalog.at(name);
        }
        return null;
    }

    @Override
    public boolean start(UUID id, String name, String args) {
        String line = args == null || args.isEmpty() ? name : name + " " + args;
        ICommand command = commands().getCommand(name);
        if (command != null) {
            requestBefore.put(id, outcomeRequests()); // Ostinato: before execute() can start a new request
            boolean ok = commands().execute(line);
            if (ok) {
                started.put(id, name);
                captureGoal(id, name); // Ostinato: record the goal if execute() already activated pathing.
                if (baritone.getCustomGoalProcess() != null && baritone.getCustomGoalProcess().isActive()) { // Ostinato:
                    seenActivity.add(id); // Ostinato:
                } // Ostinato:
            }
            return ok;
        }
        ExternalCommandHook extra = hook.get();
        if (extra != null && extra.knows(name)) {
            boolean ok = extra.start(id, name, args == null ? "" : args);
            if (ok) {
                started.put(id, name);
            }
            return ok;
        }
        return false;
    }

    @Override
    public boolean confirmStopped(UUID id) {
        String name = started.get(id);
        ExternalCommandHook extra = hook.get();
        if (name != null && extra != null && commands().getCommand(name) == null && extra.knows(name)) {
            return extra.confirmStopped(id);
        }
        // Ostinato: actually request a stop, then confirm nothing is still in control.
        try {
            if (baritone.getPathingBehavior() == null // Ostinato:
                    || !baritone.getPathingBehavior().cancelEverything() // Ostinato:
                    || baritone.getPathingBehavior().isPathing() // Ostinato:
                    || baritone.getPathingBehavior().getInProgress().isPresent() // Ostinato:
                    || baritone.getCustomGoalProcess().isActive()) { // Ostinato:
                return false; // Ostinato: an unsafe segment may still be moving after cancellation.
            } // Ostinato:
        } catch (Throwable ignored) {
            return false;
        }
        try {
            IPathingControlManager mgr = baritone.getPathingControlManager();
            if (mgr == null) { // Ostinato:
                return false; // Ostinato: cannot confirm without observing control.
            } // Ostinato:
            Optional<IBaritoneProcess> cur = mgr.mostRecentInControl();
            if (cur.isPresent() && cur.get().isActive()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    @Override
    public ExecutionState poll(UUID id) {
        String name = started.get(id);
        if (name == null) {
            return null;
        }
        // @ commands stay ambiguous; CommandProtocol will not take a terminal from poll for those.
        if (commands().getCommand(name) == null) {
            return null;
        }
        if (CommandProtocol.completesInline(name)) {
            return null;
        }
        try {
            IPathingControlManager mgr = baritone.getPathingControlManager();
            if (mgr == null) {
                return null;
            }
            Optional<IBaritoneProcess> cur = mgr.mostRecentInControl();
            if (cur.isPresent() && cur.get().isActive()) {
                seenActivity.add(id); // Ostinato:
                pin(id, cur.get()); // Ostinato: only the process this command started
                captureGoal(id, name); // Ostinato:
                return null;
            }
            // Ostinato: a recorded outcome for this command's request is its result, even if poll never saw the
            // process in control (a mine with nothing to mine can end on its first tick).
            ExecutionState recorded = recordedOutcome(id);
            if (recorded != null) {
                unverifiedIdle.remove(id);
                return recorded;
            }
            // Ostinato: an idle control slot is not completion, especially before the first process tick.
            captureGoal(id, name); // Ostinato: execute() may activate the custom-goal process on a later tick.
            if (baritone.getCustomGoalProcess() != null && baritone.getCustomGoalProcess().isActive()) { // Ostinato:
                seenActivity.add(id); // Ostinato:
                return null; // Ostinato:
            } // Ostinato:
            if (baritone.getPathingBehavior() != null // Ostinato:
                    && (baritone.getPathingBehavior().isPathing() // Ostinato:
                    || baritone.getPathingBehavior().getInProgress().isPresent())) { // Ostinato:
                seenActivity.add(id); // Ostinato:
                return null; // Ostinato:
            } // Ostinato:
            if (!seenActivity.contains(id)) { // Ostinato:
                return null; // Ostinato: still waiting for the first process tick.
            } // Ostinato:
            Goal goal = goals.get(id); // Ostinato:
            if (goal == null) { // Ostinato:
                // Ostinato: a process going idle is not a success. Only its own recorded outcome for this request is
                // (a mine that found nothing went idle too and used to report succeeded (verified)); without one the
                // command stays running with observation unknown.
                unverifiedIdle.add(id);
                return null;
            } // Ostinato:
            return goal.isInGoal(baritone.getPlayerContext().playerFeet()) // Ostinato:
                    ? ExecutionState.SUCCEEDED : ExecutionState.FAILED; // Ostinato: idle away from the captured goal is failure, not a hang.
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Ostinato: true once this command's process went idle without a recorded outcome (observation unknown). */
    @Override
    public boolean unverifiedIdle(UUID id) {
        return unverifiedIdle.contains(id);
    }

    /** Ostinato: the outcome request numbers of the processes that record outcomes, right now. */
    private Map<IBaritoneProcess, Long> outcomeRequests() {
        Map<IBaritoneProcess, Long> m = new java.util.HashMap<>();
        // Ostinato: every process that records outcomes. Each on its own: one that cannot be read leaves the others.
        java.util.List<Supplier<? extends IBaritoneProcess>> recording = java.util.List.of(baritone::getMineProcess,
                baritone::getGetToBlockProcess, baritone::getCustomGoalProcess, baritone::getBuilderProcess,
                baritone::getFarmProcess, baritone::getFollowProcess, baritone::getExploreProcess);
        for (Supplier<? extends IBaritoneProcess> get : recording) {
            try {
                IBaritoneProcess p = get.get();
                ProcessOutcome o = p == null ? null : outcomeOf(p);
                if (o != null) {
                    m.put(p, o.request());
                }
            } catch (Throwable ignored) {
                // this process cannot be compared against; a command it runs cannot be verified
            }
        }
        return m;
    }

    /** Ostinato: the process's recorded outcome ({@link ProcessOutcome#NONE} if it records none), or null if unreadable. */
    static ProcessOutcome outcomeOf(IBaritoneProcess p) {
        try {
            return p == null ? null : p.outcome();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Ostinato: pin {@code id} to {@code p} only if p records outcomes and its request advanced since the command
     * started, i.e. p is running this command's request. Any other process seen in control (one left over from earlier,
     * a survival or approach process, one with no outcome API) is never pinned; the poll keeps looking, and the outcome
     * falls back to the one recording process whose request this command started. p2c2 slice 1 rerun (21 Nov): the
     * swarm's approach process was still in control on the mine command's first poll, the command was pinned to it,
     * and the mine's recorded success was never read.
     */
    private void pin(UUID id, IBaritoneProcess p) {
        if (processOf.containsKey(id)) {
            return;
        }
        Map<IBaritoneProcess, Long> before = requestBefore.get(id);
        ProcessOutcome o = outcomeOf(p);
        if (before == null || o == null || !before.containsKey(p) || o.request() <= before.get(p)) {
            return;
        }
        processOf.put(id, p);
    }

    /**
     * Ostinato: the verified result of this command from its process's recorded outcome, or null when there is none
     * (a process that does not record outcomes, a request that is not this command's, or one not ended).
     */
    private ExecutionState recordedOutcome(UUID id) {
        Map<IBaritoneProcess, Long> before = requestBefore.get(id);
        if (before == null) {
            return null;
        }
        IBaritoneProcess p = processOf.get(id);
        if (p == null) { // never seen in control: the one recording process whose request this command started
            for (Map.Entry<IBaritoneProcess, Long> e : before.entrySet()) {
                ProcessOutcome o = outcomeOf(e.getKey());
                if (o != null && o.request() > e.getValue()) {
                    if (p != null) {
                        return null; // two started: cannot tell which is this command's
                    }
                    p = e.getKey();
                }
            }
        }
        if (p == null || !before.containsKey(p)) {
            return null;
        }
        return verdict(outcomeOf(p), before.get(p));
    }

    /**
     * Ostinato: SUCCEEDED or FAILED when the request this command started (the one right after
     * {@code requestBefore}) ended so; else null (still running, cancelled, or replaced by a later request).
     */
    static ExecutionState verdict(ProcessOutcome o, long requestBefore) {
        if (o == null || o.request() != requestBefore + 1 || !o.terminal()) {
            return null;
        }
        switch (o.state()) {
            case SUCCEEDED:
                return ExecutionState.SUCCEEDED;
            case FAILED:
                return ExecutionState.FAILED;
            default:
                return null; // cancelled from outside or unknown: not a verified result of this command
        }
    }

    // Ostinato: remember this command's goal once, including when activation is delayed.
    private void captureGoal(UUID id, String name) { // Ostinato:
        if (!"goto".equalsIgnoreCase(name) || goals.containsKey(id) || baritone.getCustomGoalProcess() == null) { // Ostinato:
            return; // Ostinato:
        } // Ostinato:
        if (!baritone.getCustomGoalProcess().isActive()) { // Ostinato:
            return; // Ostinato:
        } // Ostinato:
        Goal goal = baritone.getCustomGoalProcess().getGoal(); // Ostinato:
        if (goal != null) { // Ostinato:
            goals.put(id, goal); // Ostinato:
        } // Ostinato:
    } // Ostinato:
}
