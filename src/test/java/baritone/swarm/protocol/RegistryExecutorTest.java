package baritone.swarm.protocol; // Ostinato:

import baritone.api.IBaritone; // Ostinato:
import baritone.api.behavior.IPathingBehavior; // Ostinato:
import baritone.api.command.CommandPermission; // Ostinato:
import baritone.api.command.ICommand; // Ostinato:
import baritone.api.command.manager.ICommandManager; // Ostinato:
import baritone.api.pathing.calc.IPathingControlManager; // Ostinato:
import baritone.api.pathing.goals.Goal; // Ostinato:
import baritone.api.pathing.goals.GoalBlock; // Ostinato:
import baritone.api.process.ICustomGoalProcess; // Ostinato:
import baritone.api.process.IBaritoneProcess; // Ostinato:
import baritone.api.process.IMineProcess; // Ostinato:
import baritone.api.process.IGetToBlockProcess; // Ostinato:
import baritone.api.process.ProcessOutcome; // Ostinato:
import baritone.api.utils.IPlayerContext; // Ostinato:
import baritone.api.utils.BetterBlockPos; // Ostinato:
import baritone.api.swarm.protocol.ExecutionState; // Ostinato:
import org.junit.Test; // Ostinato:
import java.lang.reflect.Proxy; // Ostinato:
import java.util.Optional; // Ostinato:
import java.util.UUID; // Ostinato:
import java.util.function.Function; // Ostinato:
import static org.junit.Assert.*; // Ostinato:

public class RegistryExecutorTest { // Ostinato:
    private boolean active = true; // Ostinato:
    private boolean pathing; // Ostinato:
    private boolean stopAccepted = true; // Ostinato:
    private BetterBlockPos feet = new BetterBlockPos(21, 97, 12); // Ostinato:
    private Goal goal = new GoalBlock(80, 75, 80); // Ostinato:
    private ProcessOutcome mineOutcome = new ProcessOutcome(3, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE); // Ostinato: an earlier request
    private boolean mineActive; // Ostinato:
    private boolean mineInControl; // Ostinato:
    private IBaritoneProcess otherInControl; // Ostinato: another process holding control (wins over the mine)
    private final ProcessOutcome gtbOutcome = new ProcessOutcome(2, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE); // Ostinato:
    private final IGetToBlockProcess gtb = (IGetToBlockProcess) Proxy.newProxyInstance(IGetToBlockProcess.class.getClassLoader(), // Ostinato:
            new Class<?>[]{IGetToBlockProcess.class}, (p, method, args) -> { // Ostinato:
                switch (method.getName()) { // Ostinato:
                    case "outcome": return gtbOutcome; // Ostinato: an earlier request, ended long ago
                    case "isActive": return true; // Ostinato:
                    case "hashCode": return System.identityHashCode(p); // Ostinato:
                    case "equals": return p == args[0]; // Ostinato:
                    default: return null; // Ostinato:
                } // Ostinato:
            }); // Ostinato:
    private final IBaritoneProcess plain = (IBaritoneProcess) Proxy.newProxyInstance(IBaritoneProcess.class.getClassLoader(), // Ostinato:
            new Class<?>[]{IBaritoneProcess.class}, (p, method, args) -> { // Ostinato: no outcome API (like the approach)
                switch (method.getName()) { // Ostinato:
                    case "isActive": return true; // Ostinato:
                    case "hashCode": return System.identityHashCode(p); // Ostinato:
                    case "equals": return p == args[0]; // Ostinato:
                    default: return null; // Ostinato:
                } // Ostinato:
            }); // Ostinato:
    private final IMineProcess mine = (IMineProcess) Proxy.newProxyInstance(IMineProcess.class.getClassLoader(), // Ostinato:
            new Class<?>[]{IMineProcess.class}, (p, method, args) -> { // Ostinato:
                switch (method.getName()) { // Ostinato:
                    case "outcome": return mineOutcome; // Ostinato:
                    case "isActive": return mineActive; // Ostinato:
                    case "hashCode": return System.identityHashCode(p); // Ostinato:
                    case "equals": return p == args[0]; // Ostinato:
                    default: return null; // Ostinato:
                } // Ostinato:
            }); // Ostinato:

    @SuppressWarnings("unchecked") // Ostinato:
    private static <T> T proxy(Class<T> type, Function<String, Object> call) { // Ostinato:
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, // Ostinato:
                (p, method, args) -> call.apply(method.getName())); // Ostinato:
    } // Ostinato:

    private RegistryExecutor executor() { // Ostinato:
        ICommand command = proxy(ICommand.class, name -> null); // Ostinato:
        ICommandManager commands = proxy(ICommandManager.class, name -> // Ostinato:
                "getCommand".equals(name) ? command : "execute".equals(name) ? true : null); // Ostinato:
        ICustomGoalProcess process = proxy(ICustomGoalProcess.class, name -> // Ostinato:
                "isActive".equals(name) ? active : goal); // Ostinato:
        IPathingControlManager control = proxy(IPathingControlManager.class, name -> // Ostinato:
                otherInControl != null ? Optional.of(otherInControl) // Ostinato:
                        : mineInControl ? Optional.<IBaritoneProcess>of(mine) : Optional.empty()); // Ostinato:
        IPathingBehavior behavior = proxy(IPathingBehavior.class, name -> // Ostinato:
                "cancelEverything".equals(name) ? stopAccepted // Ostinato:
                        : "isPathing".equals(name) ? pathing : Optional.empty()); // Ostinato:
        IPlayerContext context = proxy(IPlayerContext.class, name -> feet); // Ostinato:
        IBaritone bot = proxy(IBaritone.class, name -> { // Ostinato:
            switch (name) { // Ostinato:
                case "getCommandManager": return commands; // Ostinato:
                case "getCustomGoalProcess": return process; // Ostinato:
                case "getPathingControlManager": return control; // Ostinato:
                case "getPathingBehavior": return behavior; // Ostinato:
                case "getPlayerContext": return context; // Ostinato:
                case "getMineProcess": return mine; // Ostinato:
                case "getGetToBlockProcess": return gtb; // Ostinato:
                default: return null; // Ostinato:
            } // Ostinato:
        }); // Ostinato:
        return new RegistryExecutor(bot, null); // Ostinato:
    } // Ostinato:

    @Test public void gotoNeedsFinishedProcessAndCapturedGoalReached() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        assertTrue(executor.start(id, "goto", "80 75 80")); // Ostinato:
        assertNull(executor.poll(id)); // Ostinato: still active, far from goal
        active = false; // Ostinato:
        pathing = true; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato: still pathing far from goal
        pathing = false; // Ostinato:
        assertEquals(ExecutionState.FAILED, executor.poll(id)); // Ostinato: idle away from goal is failure, not a hang
        UUID arrived = UUID.randomUUID(); // Ostinato:
        active = true; // Ostinato:
        feet = new BetterBlockPos(80, 75, 80); // Ostinato:
        assertTrue(executor.start(arrived, "goto", "80 75 80")); // Ostinato:
        pathing = true; // Ostinato:
        assertNull(executor.poll(arrived)); // Ostinato:
        pathing = false; // Ostinato:
        active = true; // Ostinato:
        assertNull(executor.poll(arrived)); // Ostinato:
        active = false; // Ostinato:
        goal = new GoalBlock(0, 0, 0); // Ostinato: live process goal changed; the captured one still counts
        assertEquals(ExecutionState.SUCCEEDED, executor.poll(arrived)); // Ostinato:
    } // Ostinato:

    @Test public void idleWithoutCommandGoalIsNotSuccess() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        UUID block = UUID.randomUUID(); // Ostinato:
        assertTrue(executor.start(block, "goto", "stone")); // Ostinato:
        assertNull(executor.poll(block)); // Ostinato:
    } // Ostinato:

    // Ostinato: was mineSucceedsOnceTheProcessHasRunAndGoneIdle. A process going idle is not a success (17 Nov:
    // a mine with nothing to mine reported succeeded (verified)); without a recorded outcome it is unverified.
    @Test public void aMineThatWentIdleWithoutARecordedOutcomeIsUnverifiedNotSucceeded() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        pathing = true; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        pathing = false; // Ostinato:
        assertNull("never succeeded from going idle", executor.poll(id)); // Ostinato:
        assertTrue(executor.unverifiedIdle(id)); // Ostinato:
    } // Ostinato:

    @Test public void gotoCapturesGoalOnLaterPoll() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "goto", "80 75 80")); // Ostinato:
        assertNull(executor.poll(id)); // Ostinato: execute() had not activated the process yet
        active = true; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato: late capture, still active
        active = false; // Ostinato:
        feet = new BetterBlockPos(80, 75, 80); // Ostinato:
        assertEquals(ExecutionState.SUCCEEDED, executor.poll(id)); // Ostinato:
    } // Ostinato:
    @Test public void cancelRequiresActualStopEvenWithEmptyControlSlot() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        assertTrue(executor.start(id, "goto", "80 75 80")); // Ostinato:
        stopAccepted = false; // Ostinato:
        assertFalse(executor.confirmStopped(id)); // Ostinato:
        stopAccepted = true; // Ostinato:
        assertFalse(executor.confirmStopped(id)); // Ostinato:
        active = false; pathing = true; // Ostinato:
        assertFalse(executor.confirmStopped(id)); // Ostinato:
        pathing = false; // Ostinato:
        assertTrue(executor.confirmStopped(id)); // Ostinato:
    } // Ostinato:

    @Test public void swarmCancelIsSwarmEvenWhenChatCancelIsOwner() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        assertEquals(CommandPermission.SWARM, executor.describe("cancel").permission); // Ostinato:
        assertEquals(CommandPermission.SWARM, executor.describe("forcecancel").permission); // Ostinato:
        assertFalse(executor.describe("cancel").ownerEmergency); // Ostinato: owner pause still outranks swarm cancel
    } // Ostinato:
    private static ProcessOutcome mineRequest(long request, ProcessOutcome.State state, ProcessOutcome.Reason why) { // Ostinato:
        return new ProcessOutcome(request, state, why); // Ostinato:
    } // Ostinato:

    @Test public void aMineWithNothingToMineIsFailedEvenIfItEndedBeforeAnyPoll() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato:
        mineOutcome = mineRequest(4, ProcessOutcome.State.FAILED, ProcessOutcome.Reason.MISSING_RESOURCE); // Ostinato: ended on its first tick
        assertEquals(ExecutionState.FAILED, executor.poll(id)); // Ostinato:
        assertFalse(executor.unverifiedIdle(id)); // Ostinato:
    } // Ostinato:

    @Test public void aMineThatMinedSomethingSucceedsFromItsRecordedOutcome() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato:
        mineOutcome = mineRequest(4, ProcessOutcome.State.RUNNING, ProcessOutcome.Reason.NONE); // Ostinato:
        mineInControl = true; // Ostinato:
        mineActive = true; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        mineActive = false; // Ostinato: went idle before recording its outcome: not yet a result
        mineInControl = false; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        mineOutcome = mineRequest(4, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE); // Ostinato:
        assertEquals(ExecutionState.SUCCEEDED, executor.poll(id)); // Ostinato:
    } // Ostinato:

    @Test public void anEarlierRequestsSuccessIsNotThisCommands() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato: request 3 ended SUCCEEDED before it
        mineInControl = true; // Ostinato:
        mineActive = true; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        mineActive = false; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        assertTrue(executor.unverifiedIdle(id)); // Ostinato:
    } // Ostinato:

    @Test public void cancelledOrReplacedRequestsAreNotVerifiedResults() { // Ostinato:
        for (ProcessOutcome after : new ProcessOutcome[] { // Ostinato:
                mineRequest(4, ProcessOutcome.State.CANCELLED, ProcessOutcome.Reason.CANCELLED), // Ostinato:
                mineRequest(5, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE)}) { // Ostinato: a later command's
            mineOutcome = mineRequest(3, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE); // Ostinato:
            RegistryExecutor executor = executor(); // Ostinato:
            UUID id = UUID.randomUUID(); // Ostinato:
            active = false; // Ostinato:
            assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato:
            mineInControl = true; // Ostinato:
            mineActive = true; // Ostinato:
            mineOutcome = mineRequest(4, ProcessOutcome.State.RUNNING, ProcessOutcome.Reason.NONE); // Ostinato:
            assertNull(executor.poll(id)); // Ostinato:
            mineActive = false; // Ostinato:
            mineOutcome = after; // Ostinato:
            assertNull(after.toString(), executor.poll(id)); // Ostinato:
            assertTrue(after.toString(), executor.unverifiedIdle(id)); // Ostinato:
            mineInControl = false; // Ostinato:
        } // Ostinato:
    } // Ostinato:

    @Test public void verdictTakesOnlyTheRequestRightAfterTheStart() { // Ostinato:
        ProcessOutcome failed = mineRequest(8, ProcessOutcome.State.FAILED, ProcessOutcome.Reason.UNREACHABLE); // Ostinato:
        assertEquals(ExecutionState.FAILED, RegistryExecutor.verdict(failed, 7)); // Ostinato:
        assertNull(RegistryExecutor.verdict(failed, 8)); // Ostinato:
        assertNull(RegistryExecutor.verdict(failed, 6)); // Ostinato:
        assertNull(RegistryExecutor.verdict(mineRequest(8, ProcessOutcome.State.RUNNING, ProcessOutcome.Reason.NONE), 7)); // Ostinato:
        assertNull(RegistryExecutor.verdict(mineRequest(8, ProcessOutcome.State.UNKNOWN, ProcessOutcome.Reason.UNKNOWN), 7)); // Ostinato:
        assertNull(RegistryExecutor.verdict(null, 7)); // Ostinato:
    } // Ostinato:
    // Ostinato: p2c2 slice 1 rerun (21 Nov). The mine's result must not be lost to a process that happened to hold control
    // on the command's first poll.
    private void mineSucceedsBehind(IBaritoneProcess first) { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato:
        mineOutcome = mineRequest(4, ProcessOutcome.State.RUNNING, ProcessOutcome.Reason.NONE); // Ostinato:
        otherInControl = first; // Ostinato: still in control on the first poll
        assertNull(executor.poll(id)); // Ostinato:
        otherInControl = null; // Ostinato: now the mine runs
        mineInControl = true; // Ostinato:
        mineActive = true; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        mineActive = false; // Ostinato:
        mineInControl = false; // Ostinato:
        mineOutcome = mineRequest(4, ProcessOutcome.State.SUCCEEDED, ProcessOutcome.Reason.NONE); // Ostinato:
        assertEquals(ExecutionState.SUCCEEDED, executor.poll(id)); // Ostinato:
        assertFalse(executor.unverifiedIdle(id)); // Ostinato:
    } // Ostinato:

    @Test public void anotherProcessInControlAtTheFirstPollDoesNotTakeTheMinesResult() { // Ostinato:
        mineSucceedsBehind(gtb); // Ostinato: records outcomes, but its request did not advance for this command
    } // Ostinato:

    @Test public void aProcessWithNoOutcomeApiInControlFirstDoesNotTakeTheMinesResult() { // Ostinato:
        mineSucceedsBehind(plain); // Ostinato:
    } // Ostinato:
} // Ostinato:
