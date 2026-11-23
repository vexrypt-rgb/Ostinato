package baritone.swarm.protocol;

import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.command.ICommand;
import baritone.api.command.manager.ICommandManager;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IExploreProcess;
import baritone.api.process.IFarmProcess;
import baritone.api.process.IFollowProcess;
import baritone.api.process.ProcessOutcome;
import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Ostinato: the most-used commands that used to go idle with no recorded outcome (tunnel, farm, follow, explore,
 * build, path/come) report their process's own outcome for the request they started, the way mine does: succeeded
 * or failed from the record, never from the process going idle, and a cancelled or earlier request is no result.
 */
public class IdleOutcomeTest {

    /** One recording process: its outcome and whether it is active, changed by the test. */
    private static final class Rec {
        ProcessOutcome outcome = new ProcessOutcome(2, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE);
        boolean active;
        IBaritoneProcess proxy;
    }

    private final Map<String, Rec> procs = new HashMap<>();
    private Rec inControl;

    private Rec rec(String name) {
        return procs.get(name);
    }

    @SuppressWarnings("unchecked")
    private <T extends IBaritoneProcess> T recording(Class<T> type, String name) {
        Rec r = new Rec();
        r.proxy = (IBaritoneProcess) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            switch (m.getName()) {
                case "outcome": return r.outcome;
                case "isActive": return r.active;
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                case "getGoal": return null;
                default: return null;
            }
        });
        procs.put(name, r);
        return (T) r.proxy;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> call.apply(m.getName()));
    }

    private RegistryExecutor executor() {
        ICommand command = proxy(ICommand.class, n -> null);
        ICommandManager commands = proxy(ICommandManager.class, n -> "getCommand".equals(n) ? command : "execute".equals(n) ? true : null);
        ICustomGoalProcess custom = recording(ICustomGoalProcess.class, "custom");
        IBuilderProcess builder = recording(IBuilderProcess.class, "builder");
        IFarmProcess farm = recording(IFarmProcess.class, "farm");
        IFollowProcess follow = recording(IFollowProcess.class, "follow");
        IExploreProcess explore = recording(IExploreProcess.class, "explore");
        IPathingControlManager control = proxy(IPathingControlManager.class, n ->
                inControl != null && inControl.active ? Optional.of(inControl.proxy) : Optional.empty());
        IPathingBehavior behavior = proxy(IPathingBehavior.class, n -> "isPathing".equals(n) ? false
                : "cancelEverything".equals(n) ? true : Optional.empty());
        IPlayerContext context = proxy(IPlayerContext.class, n -> new BetterBlockPos(0, 64, 0));
        IBaritone bot = proxy(IBaritone.class, n -> {
            switch (n) {
                case "getCommandManager": return commands;
                case "getCustomGoalProcess": return custom;
                case "getBuilderProcess": return builder;
                case "getFarmProcess": return farm;
                case "getFollowProcess": return follow;
                case "getExploreProcess": return explore;
                case "getPathingControlManager": return control;
                case "getPathingBehavior": return behavior;
                case "getPlayerContext": return context;
                default: return null;
            }
        });
        return new RegistryExecutor(bot, null);
    }

    private static ProcessOutcome req(long n, ProcessOutcome.State s, ProcessOutcome.Reason why) {
        return new ProcessOutcome(n, s, why);
    }

    /** The command runs on {@code proc} (seen in control), then the process ends the request as {@code end}. */
    private ExecutionState run(String command, String args, String proc, ProcessOutcome end) {
        RegistryExecutor executor = executor();
        UUID id = UUID.randomUUID();
        assertTrue(executor.start(id, command, args));
        Rec r = rec(proc);
        r.outcome = req(3, ProcessOutcome.State.RUNNING, ProcessOutcome.Reason.NONE);
        r.active = true;
        inControl = r;
        assertNull(command + " still running", executor.poll(id));
        r.active = false;
        r.outcome = end;
        return executor.poll(id);
    }

    private static final ProcessOutcome OK = req(3, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE);
    private static final ProcessOutcome NOTHING = req(3, ProcessOutcome.State.FAILED, ProcessOutcome.Reason.MISSING_RESOURCE);

    @Test
    public void farmReportsItsRecordedOutcome() {
        assertEquals(ExecutionState.SUCCEEDED, run("farm", "", "farm", OK));
        assertEquals(ExecutionState.FAILED, run("farm", "", "farm", NOTHING));
    }

    @Test
    public void buildAndTunnelReportTheBuildersOutcome() {
        assertEquals(ExecutionState.SUCCEEDED, run("build", "house", "builder", OK));
        assertEquals(ExecutionState.SUCCEEDED, run("tunnel", "2 1 8", "builder", OK));
        assertEquals("a tunnel with nothing to dig is a no-op, not a success", ExecutionState.FAILED,
                run("tunnel", "2 1 8", "builder", NOTHING));
    }

    @Test
    public void followFailsWhenItLosesItsTarget() {
        assertEquals(ExecutionState.FAILED, run("follow", "players", "follow",
                req(3, ProcessOutcome.State.FAILED, ProcessOutcome.Reason.INVALIDATED_GOAL)));
    }

    @Test
    public void exploreReportsItsRecordedOutcome() {
        assertEquals(ExecutionState.SUCCEEDED, run("explore", "", "explore", OK));
        assertEquals(ExecutionState.FAILED, run("explore", "", "explore",
                req(3, ProcessOutcome.State.FAILED, ProcessOutcome.Reason.UNREACHABLE)));
    }

    @Test
    public void pathComeAndPlainTunnelReportTheCustomGoalOutcome() {
        assertEquals(ExecutionState.SUCCEEDED, run("come", "", "custom", OK));
        assertEquals(ExecutionState.FAILED, run("path", "", "custom",
                req(3, ProcessOutcome.State.FAILED, ProcessOutcome.Reason.UNREACHABLE)));
        assertEquals(ExecutionState.FAILED, run("tunnel", "", "custom",
                req(3, ProcessOutcome.State.FAILED, ProcessOutcome.Reason.UNREACHABLE)));
    }

    @Test
    public void aRequestThatEndedBeforeAnyPollStillCounts() {
        RegistryExecutor executor = executor();
        UUID id = UUID.randomUUID();
        assertTrue(executor.start(id, "farm", ""));
        rec("farm").outcome = NOTHING; // nothing to farm: ended on its first tick, never seen in control
        assertEquals(ExecutionState.FAILED, executor.poll(id));
    }

    @Test
    public void cancelledOrEarlierRequestsAreNoResult() {
        assertNull(run("farm", "", "farm", req(3, ProcessOutcome.State.CANCELLED, ProcessOutcome.Reason.CANCELLED)));
        RegistryExecutor executor = executor();
        UUID id = UUID.randomUUID();
        assertTrue(executor.start(id, "follow", "players"));
        Rec r = rec("follow");
        r.active = true;
        inControl = r;
        assertNull(executor.poll(id));
        r.active = false; // request 2 (ended long before this command) is all there is
        assertNull("an earlier request's success is not this command's", executor.poll(id));
        assertTrue(executor.unverifiedIdle(id));
    }
}
