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
                captureGoal(id, name); // Ostinato:
                return null;
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
                return ExecutionState.SUCCEEDED; // Ostinato: a non-goto process is done once it has run and gone idle.
            } // Ostinato:
            return goal.isInGoal(baritone.getPlayerContext().playerFeet()) // Ostinato:
                    ? ExecutionState.SUCCEEDED : ExecutionState.FAILED; // Ostinato: idle away from the captured goal is failure, not a hang.
        } catch (Throwable ignored) {
            return null;
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
