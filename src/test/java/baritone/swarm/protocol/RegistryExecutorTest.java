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
        IPathingControlManager control = proxy(IPathingControlManager.class, name -> Optional.empty()); // Ostinato:
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

    @Test public void mineSucceedsOnceTheProcessHasRunAndGoneIdle() { // Ostinato:
        RegistryExecutor executor = executor(); // Ostinato:
        UUID id = UUID.randomUUID(); // Ostinato:
        active = false; // Ostinato:
        assertTrue(executor.start(id, "mine", "diamond_ore")); // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        pathing = true; // Ostinato:
        assertNull(executor.poll(id)); // Ostinato:
        pathing = false; // Ostinato:
        assertEquals(ExecutionState.SUCCEEDED, executor.poll(id)); // Ostinato:
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
} // Ostinato:
